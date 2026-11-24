package com.createplus.systems.economy;

import com.createplus.systems.CreatePlusSystemsMod;
import com.createplus.systems.core.DatabaseManager;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Core Economy business logic: Admin Shop, Quick Sell, Black Market Daily Contracts,
 * Atomic Auction House (/ah), Offline Mailbox, and MariaDB Audit Log.
 */
public final class EconomyService {
    public static final double AH_TAX_RATE = 0.05; // 5% money sink
    public static final int AH_DURATION_HOURS = 48;
    public static final int AH_MAX_ACTIVE_PER_PLAYER = 12;
    public static final int AH_MIN_PRICE = 1;
    public static final int AH_MAX_PRICE = 10_000_000;

    private final DatabaseManager db;
    private volatile List<ShopItem> shopItems = List.of();
    private volatile Map<String, Integer> sellPriceByItemId = Map.of();
    private volatile boolean ahPaused = false;

    public EconomyService(DatabaseManager db) {
        this.db = db;
    }

    public boolean isReady() {
        return db.isConfigured();
    }

    public boolean isAhPaused() {
        return ahPaused;
    }

    public void setAhPaused(boolean paused) {
        this.ahPaused = paused;
    }

    public CompletableFuture<Void> initialize() {
        if (!db.isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.runAsync(conn -> {
            EconomySchema.ensureTables(conn);
            loadShopCache(conn);
            expireStaleListings(conn);
        });
    }

    public CompletableFuture<Integer> reloadShop() {
        return db.supplyAsync(conn -> {
            EconomySchema.ensureTodayContracts(conn, LocalDate.now(EconomySchema.SERVER_ZONE));
            return loadShopCache(conn);
        });
    }

    private int loadShopCache(Connection conn) throws SQLException {
        List<ShopItem> loaded = new ArrayList<>();
        Map<String, Integer> sellMap = new HashMap<>();
        String sql = """
                SELECT id, category, display_name, item_id, item_snbt, amount, buy_price, sell_price, sort_order
                FROM cp_shop_items
                WHERE active = 1
                ORDER BY category ASC, sort_order ASC, id ASC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Integer buy = (Integer) rs.getObject("buy_price");
                Integer sell = (Integer) rs.getObject("sell_price");
                ShopItem item = new ShopItem(
                        rs.getInt("id"),
                        rs.getString("category"),
                        rs.getString("display_name"),
                        rs.getString("item_id"),
                        rs.getString("item_snbt"),
                        Math.max(1, rs.getInt("amount")),
                        buy,
                        sell,
                        rs.getInt("sort_order")
                );
                loaded.add(item);
                if (sell != null && sell > 0) {
                    int unit = Math.max(1, sell / item.amount());
                    sellMap.merge(item.itemId(), unit, Math::max);
                }
            }
        }
        this.shopItems = List.copyOf(loaded);
        this.sellPriceByItemId = Map.copyOf(sellMap);
        CreatePlusSystemsMod.LOGGER.info("Economy: loaded {} shop entries ({} sellable item IDs).", loaded.size(), sellMap.size());
        return loaded.size();
    }

    public Map<String, List<ShopItem>> getBuyableCategories() {
        Map<String, List<ShopItem>> categories = new LinkedHashMap<>();
        for (ShopItem item : shopItems) {
            if (item.buyPrice() != null && item.buyPrice() > 0) {
                categories.computeIfAbsent(item.category(), k -> new ArrayList<>()).add(item);
            }
        }
        return categories;
    }

    public int getSellUnitPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return sellPriceByItemId.getOrDefault(ItemCodecUtil.itemIdOf(stack), 0);
    }

    /**
     * Executes an Admin Shop purchase on the main server thread.
     */
    public void buyFromShop(ServerPlayer player, ShopItem shopItem, int batches) {
        if (shopItem.buyPrice() == null || shopItem.buyPrice() <= 0) {
            player.sendSystemMessage(ItemCodecUtil.text("Este item nao esta a venda.", ChatFormatting.RED));
            return;
        }
        int mult = Math.max(1, batches);
        long totalCostLong = (long) shopItem.buyPrice() * mult;
        if (totalCostLong > Integer.MAX_VALUE) {
            player.sendSystemMessage(ItemCodecUtil.text("Quantidade invalida.", ChatFormatting.RED));
            return;
        }
        int totalCost = (int) totalCostLong;
        int totalCount = shopItem.amount() * mult;

        ItemStack template = ItemCodecUtil.fromIdOrSnbt(shopItem.itemId(), shopItem.itemSnbt(), 1, player.registryAccess());
        if (template.isEmpty()) {
            player.sendSystemMessage(ItemCodecUtil.text("Item invalido na configuracao da loja: " + shopItem.itemId(), ChatFormatting.RED));
            return;
        }

        if (!NumismaticsBridge.deduct(player.getUUID(), totalCost)) {
            player.sendSystemMessage(ItemCodecUtil.text(
                    "Saldo insuficiente! Precisa de " + NumismaticsBridge.formatSpurs(totalCost)
                            + " (seu saldo: " + NumismaticsBridge.formatSpurs(NumismaticsBridge.getBalance(player.getUUID())) + ").",
                    ChatFormatting.RED
            ));
            return;
        }

        giveItemOrDrop(player, template, totalCount);
        player.sendSystemMessage(ItemCodecUtil.text(
                "Compra concluida: " + totalCount + "x " + shopItem.displayName()
                        + " por " + NumismaticsBridge.formatSpurs(totalCost) + ".",
                ChatFormatting.GREEN
        ));
        logAsync(player.getUUID(), player.getGameProfile().getName(), "SHOP_BUY", -totalCost,
                shopItem.itemId(), totalCount, "shop_id=" + shopItem.id());
    }

    public void creditQuickSell(ServerPlayer player, int totalSpurs, int totalItems) {
        if (totalSpurs <= 0) {
            return;
        }
        NumismaticsBridge.deposit(player.getUUID(), totalSpurs);
        player.sendSystemMessage(ItemCodecUtil.text(
                "Venda concluida: " + totalItems + " itens por " + NumismaticsBridge.formatSpurs(totalSpurs) + "!",
                ChatFormatting.GREEN
        ));
        logAsync(player.getUUID(), player.getGameProfile().getName(), "SHOP_SELL", totalSpurs,
                "batch", totalItems, "quick_sell_gui");
    }

    // ==================== BLACK MARKET DAILY CONTRACTS ====================

    public CompletableFuture<List<DailyContractView>> getTodayContracts(UUID playerUuid) {
        LocalDate today = LocalDate.now(EconomySchema.SERVER_ZONE);
        return db.supplyAsync(conn -> {
            EconomySchema.ensureTodayContracts(conn, today);
            String sql = """
                    SELECT c.id, c.profession, c.display_name, c.item_id, c.unit_price, c.daily_cap,
                           COALESCE(p.delivered, 0) AS delivered
                    FROM cp_daily_contracts c
                    LEFT JOIN cp_daily_contract_progress p
                           ON p.contract_id = c.id AND p.player_uuid = ?
                    WHERE c.contract_date = ?
                    ORDER BY c.id ASC
                    """;
            List<DailyContractView> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DailyContractView(
                                rs.getInt("id"),
                                rs.getString("profession"),
                                rs.getString("display_name"),
                                rs.getString("item_id"),
                                rs.getInt("unit_price"),
                                rs.getInt("daily_cap"),
                                rs.getInt("delivered")
                        ));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> resetTodayContracts() {
        LocalDate today = LocalDate.now(EconomySchema.SERVER_ZONE);
        return db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cp_daily_contracts WHERE contract_date = ?")) {
                ps.setDate(1, java.sql.Date.valueOf(today));
                ps.executeUpdate();
            }
            EconomySchema.ensureTodayContracts(conn, today);
        });
    }

    /**
     * Delivers matching items from player inventory toward a daily Black Market contract.
     * Removes items synchronously on server thread first, then records progress in MariaDB.
     */
    public void deliverToContract(ServerPlayer player, DailyContractView contract, int maxToDeliver) {
        int remaining = Math.max(0, contract.dailyCap() - contract.delivered());
        if (remaining <= 0) {
            player.sendSystemMessage(ItemCodecUtil.text("Voce ja atingiu o teto diario deste contrato!", ChatFormatting.YELLOW));
            return;
        }
        int target = Math.min(remaining, Math.max(1, maxToDeliver));
        List<ItemStack> removedStacks = removeMatchingItems(player, contract.itemId(), target);
        int taken = removedStacks.stream().mapToInt(ItemStack::getCount).sum();
        if (taken <= 0) {
            player.sendSystemMessage(ItemCodecUtil.text(
                    "Voce nao possui " + contract.displayName() + " (" + contract.itemId() + ") no inventario.",
                    ChatFormatting.RED
            ));
            return;
        }

        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        String playerName = player.getGameProfile().getName();

        db.supplyAsync(conn -> {
            conn.setAutoCommit(false);
            try {
                int currentDelivered = 0;
                try (PreparedStatement sel = conn.prepareStatement(
                        "SELECT delivered FROM cp_daily_contract_progress WHERE contract_id = ? AND player_uuid = ? FOR UPDATE")) {
                    sel.setInt(1, contract.id());
                    sel.setString(2, uuid.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            currentDelivered = rs.getInt(1);
                        }
                    }
                }
                int allowed = Math.max(0, Math.min(taken, contract.dailyCap() - currentDelivered));
                if (allowed <= 0) {
                    conn.rollback();
                    return 0;
                }
                try (PreparedStatement up = conn.prepareStatement("""
                        INSERT INTO cp_daily_contract_progress (contract_id, player_uuid, delivered)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE delivered = delivered + VALUES(delivered)
                        """)) {
                    up.setInt(1, contract.id());
                    up.setString(2, uuid.toString());
                    up.setInt(3, allowed);
                    up.executeUpdate();
                }
                conn.commit();
                return allowed;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }).whenComplete((accepted, err) -> {
            if (server == null) return;
            server.execute(() -> {
                if (err != null || accepted == null || accepted <= 0) {
                    // Refund removed items
                    for (ItemStack s : removedStacks) {
                        giveItemOrDrop(player, s, s.getCount());
                    }
                    player.sendSystemMessage(ItemCodecUtil.text("Nao foi possivel entregar o contrato agora.", ChatFormatting.RED));
                    return;
                }
                int refundCount = taken - accepted;
                if (refundCount > 0) {
                    ItemStack template = ItemCodecUtil.fromIdOrSnbt(contract.itemId(), null, 1, player.registryAccess());
                    if (!template.isEmpty()) {
                        giveItemOrDrop(player, template, refundCount);
                    }
                }
                int payout = accepted * contract.unitPrice();
                NumismaticsBridge.deposit(uuid, payout);
                player.sendSystemMessage(ItemCodecUtil.text(
                        "Contrato [" + contract.profession() + "]: entregou " + accepted + "x " + contract.displayName()
                                + " e recebeu " + NumismaticsBridge.formatSpurs(payout) + "!",
                        ChatFormatting.GREEN
                ));
                logAsync(uuid, playerName, "CONTRACT_SELL", payout, contract.itemId(), accepted, "contract_id=" + contract.id());
            });
        });
    }

    // ==================== AUCTION HOUSE (/ah) ====================

    public void createListingFromHand(ServerPlayer player, int price) {
        if (ahPaused) {
            player.sendSystemMessage(ItemCodecUtil.text("O Mercado (/ah) esta temporariamente pausado.", ChatFormatting.RED));
            return;
        }
        if (price < AH_MIN_PRICE || price > AH_MAX_PRICE) {
            player.sendSystemMessage(ItemCodecUtil.text(
                    "Preco invalido. Use entre " + AH_MIN_PRICE + " e " + AH_MAX_PRICE + " Spurs.",
                    ChatFormatting.RED
            ));
            return;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            player.sendSystemMessage(ItemCodecUtil.text("Segure o item que deseja anunciar na mao principal.", ChatFormatting.RED));
            return;
        }
        HolderLookup.Provider registries = player.registryAccess();
        Optional<String> snbtOpt = ItemCodecUtil.serialize(hand, registries);
        if (snbtOpt.isEmpty()) {
            player.sendSystemMessage(ItemCodecUtil.text("Nao foi possivel serializar este item.", ChatFormatting.RED));
            return;
        }

        // Remove from hand immediately on server thread to prevent duping
        ItemStack taken = hand.copy();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        String itemId = ItemCodecUtil.itemIdOf(taken);
        String rawName = taken.getHoverName().getString();
        String itemName = rawName.length() > 120 ? rawName.substring(0, 120) : rawName;
        int itemCount = taken.getCount();
        String snbt = snbtOpt.get();
        UUID sellerUuid = player.getUUID();
        String sellerName = player.getGameProfile().getName();
        MinecraftServer server = player.getServer();

        db.supplyAsync(conn -> {
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM cp_ah_listings WHERE seller_uuid = ? AND status = 'ACTIVE'")) {
                countPs.setString(1, sellerUuid.toString());
                try (ResultSet rs = countPs.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= AH_MAX_ACTIVE_PER_PLAYER) {
                        return -1L;
                    }
                }
            }
            Timestamp expires = Timestamp.from(Instant.now().plus(AH_DURATION_HOURS, ChronoUnit.HOURS));
            try (PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO cp_ah_listings (seller_uuid, seller_name, item_id, item_name, item_count, item_snbt, price, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, sellerUuid.toString());
                ins.setString(2, sellerName);
                ins.setString(3, itemId);
                ins.setString(4, itemName);
                ins.setInt(5, itemCount);
                ins.setString(6, snbt);
                ins.setInt(7, price);
                ins.setTimestamp(8, expires);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 1L;
                }
            }
        }).whenComplete((listingId, err) -> {
            if (server == null) return;
            server.execute(() -> {
                if (err != null || listingId == null || listingId < 0) {
                    giveItemOrDrop(player, taken, taken.getCount());
                    if (listingId != null && listingId == -1L) {
                        player.sendSystemMessage(ItemCodecUtil.text(
                                "Limite de " + AH_MAX_ACTIVE_PER_PLAYER + " anuncios ativos atingido.",
                                ChatFormatting.RED
                        ));
                    } else {
                        player.sendSystemMessage(ItemCodecUtil.text("Erro ao anunciar no /ah. Item devolvido.", ChatFormatting.RED));
                    }
                    return;
                }
                player.sendSystemMessage(ItemCodecUtil.text(
                        "Anunciado no /ah (#" + listingId + "): " + itemCount + "x " + taken.getHoverName().getString()
                                + " por " + NumismaticsBridge.formatSpurs(price) + " (expira em " + AH_DURATION_HOURS + "h).",
                        ChatFormatting.GREEN
                ));
                logAsync(sellerUuid, sellerName, "AH_LIST", 0, itemId, itemCount, "listing_id=" + listingId + ",price=" + price);
            });
        });
    }

    public CompletableFuture<List<AhListing>> getActiveListings() {
        return db.supplyAsync(conn -> {
            expireStaleListings(conn);
            String sql = """
                    SELECT id, seller_uuid, seller_name, item_id, item_name, item_count, item_snbt, price, expires_at
                    FROM cp_ah_listings
                    WHERE status = 'ACTIVE' AND expires_at > NOW()
                    ORDER BY created_at DESC
                    LIMIT 200
                    """;
            return readListings(conn, sql, null);
        });
    }

    public CompletableFuture<List<AhListing>> getPlayerListings(UUID sellerUuid) {
        return db.supplyAsync(conn -> {
            expireStaleListings(conn);
            String sql = """
                    SELECT id, seller_uuid, seller_name, item_id, item_name, item_count, item_snbt, price, expires_at
                    FROM cp_ah_listings
                    WHERE status = 'ACTIVE' AND seller_uuid = ?
                    ORDER BY created_at DESC
                    """;
            return readListings(conn, sql, sellerUuid.toString());
        });
    }

    private List<AhListing> readListings(Connection conn, String sql, String param) throws SQLException {
        List<AhListing> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AhListing(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("seller_uuid")),
                            rs.getString("seller_name"),
                            rs.getString("item_id"),
                            rs.getString("item_name"),
                            rs.getInt("item_count"),
                            rs.getString("item_snbt"),
                            rs.getInt("price"),
                            rs.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
        }
        return list;
    }

    /**
     * Atomic Auction House purchase:
     * 1) Deduct buyer balance on server thread.
     * 2) Atomic UPDATE WHERE status='ACTIVE' in MariaDB.
     * 3) If won, deliver item to buyer & credit net payout (price - tax) to seller mailbox.
     * 4) If lost race, refund buyer immediately on server thread.
     */
    public void buyListing(ServerPlayer buyer, AhListing listing) {
        if (ahPaused) {
            buyer.sendSystemMessage(ItemCodecUtil.text("O Mercado (/ah) esta temporariamente pausado.", ChatFormatting.RED));
            return;
        }
        if (buyer.getUUID().equals(listing.sellerUuid())) {
            buyer.sendSystemMessage(ItemCodecUtil.text("Voce nao pode comprar seu proprio anuncio. Use 'Meus Anuncios' para cancelar.", ChatFormatting.YELLOW));
            return;
        }
        int price = listing.price();
        if (!NumismaticsBridge.deduct(buyer.getUUID(), price)) {
            buyer.sendSystemMessage(ItemCodecUtil.text(
                    "Saldo insuficiente! Precisa de " + NumismaticsBridge.formatSpurs(price) + ".",
                    ChatFormatting.RED
            ));
            return;
        }

        int tax = (int) Math.floor(price * AH_TAX_RATE);
        int sellerPayout = Math.max(1, price - tax);
        UUID buyerUuid = buyer.getUUID();
        String buyerName = buyer.getGameProfile().getName();
        MinecraftServer server = buyer.getServer();

        db.supplyAsync(conn -> {
            conn.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement up = conn.prepareStatement("""
                        UPDATE cp_ah_listings
                        SET status = 'SOLD', buyer_uuid = ?, closed_at = NOW()
                        WHERE id = ? AND status = 'ACTIVE' AND expires_at > NOW()
                        """)) {
                    up.setString(1, buyerUuid.toString());
                    up.setLong(2, listing.id());
                    updated = up.executeUpdate();
                }
                if (updated != 1) {
                    conn.rollback();
                    return false;
                }
                try (PreparedStatement mail = conn.prepareStatement("""
                        INSERT INTO cp_ah_mailbox (player_uuid, kind, spurs, reason)
                        VALUES (?, 'COINS', ?, ?)
                        """)) {
                    mail.setString(1, listing.sellerUuid().toString());
                    mail.setInt(2, sellerPayout);
                    mail.setString(3, "Venda no /ah: " + listing.itemCount() + "x " + listing.itemName() + " (comprado por " + buyerName + ")");
                    mail.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }).whenComplete((won, err) -> {
            if (server == null) return;
            server.execute(() -> {
                if (err != null || !Boolean.TRUE.equals(won)) {
                    NumismaticsBridge.deposit(buyerUuid, price);
                    buyer.sendSystemMessage(ItemCodecUtil.text("Este anuncio ja foi comprado ou expirou. Seu saldo foi estornado.", ChatFormatting.YELLOW));
                    return;
                }
                Optional<ItemStack> stackOpt = ItemCodecUtil.deserialize(listing.itemSnbt(), buyer.registryAccess());
                if (stackOpt.isPresent()) {
                    giveItemOrDrop(buyer, stackOpt.get(), stackOpt.get().getCount());
                } else {
                    ItemStack fallback = ItemCodecUtil.fromIdOrSnbt(listing.itemId(), null, listing.itemCount(), buyer.registryAccess());
                    if (!fallback.isEmpty()) {
                        giveItemOrDrop(buyer, fallback, fallback.getCount());
                    }
                }
                buyer.sendSystemMessage(ItemCodecUtil.text(
                        "Voce comprou " + listing.itemCount() + "x " + listing.itemName()
                                + " por " + NumismaticsBridge.formatSpurs(price) + "!",
                        ChatFormatting.GREEN
                ));
                ServerPlayer onlineSeller = server.getPlayerList().getPlayer(listing.sellerUuid());
                if (onlineSeller != null) {
                    onlineSeller.sendSystemMessage(ItemCodecUtil.text(
                            "Seu anuncio no /ah (" + listing.itemCount() + "x " + listing.itemName() + ") foi vendido! "
                                    + "Use /ah caixa para resgatar " + NumismaticsBridge.formatSpurs(sellerPayout) + ".",
                            ChatFormatting.GOLD
                    ));
                }
                logAsync(buyerUuid, buyerName, "AH_BUY", -price, listing.itemId(), listing.itemCount(),
                        "listing_id=" + listing.id() + ",seller=" + listing.sellerName() + ",tax=" + tax);
            });
        });
    }

    public void cancelListing(ServerPlayer player, AhListing listing) {
        UUID uuid = player.getUUID();
        MinecraftServer server = player.getServer();
        db.supplyAsync(conn -> {
            conn.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement up = conn.prepareStatement("""
                        UPDATE cp_ah_listings
                        SET status = 'CANCELLED', closed_at = NOW()
                        WHERE id = ? AND seller_uuid = ? AND status = 'ACTIVE'
                        """)) {
                    up.setLong(1, listing.id());
                    up.setString(2, uuid.toString());
                    updated = up.executeUpdate();
                }
                if (updated != 1) {
                    conn.rollback();
                    return false;
                }
                try (PreparedStatement mail = conn.prepareStatement("""
                        INSERT INTO cp_ah_mailbox (player_uuid, kind, item_snbt, reason)
                        VALUES (?, 'ITEM', ?, ?)
                        """)) {
                    mail.setString(1, uuid.toString());
                    mail.setString(2, listing.itemSnbt());
                    mail.setString(3, "Anuncio cancelado: " + listing.itemCount() + "x " + listing.itemName());
                    mail.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }).whenComplete((ok, err) -> {
            if (server == null) return;
            server.execute(() -> {
                if (Boolean.TRUE.equals(ok)) {
                    player.sendSystemMessage(ItemCodecUtil.text("Anuncio cancelado! Resgate o item em /ah caixa.", ChatFormatting.YELLOW));
                } else {
                    player.sendSystemMessage(ItemCodecUtil.text("Nao foi possivel cancelar (talvez ja tenha sido vendido).", ChatFormatting.RED));
                }
            });
        });
    }

    public CompletableFuture<List<MailboxEntry>> getUnclaimedMailbox(UUID playerUuid) {
        return db.supplyAsync(conn -> {
            expireStaleListings(conn);
            List<MailboxEntry> out = new ArrayList<>();
            String sql = """
                    SELECT id, kind, spurs, item_snbt, reason
                    FROM cp_ah_mailbox
                    WHERE player_uuid = ? AND claimed = 0
                    ORDER BY created_at ASC
                    LIMIT 45
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new MailboxEntry(
                                rs.getLong("id"),
                                rs.getString("kind"),
                                rs.getInt("spurs"),
                                rs.getString("item_snbt"),
                                rs.getString("reason")
                        ));
                    }
                }
            }
            return out;
        });
    }

    public void claimMailboxEntry(ServerPlayer player, MailboxEntry entry, Runnable onAfterClaim) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        MinecraftServer server = player.getServer();
        db.supplyAsync(conn -> {
            try (PreparedStatement up = conn.prepareStatement("""
                    UPDATE cp_ah_mailbox
                    SET claimed = 1, claimed_at = NOW()
                    WHERE id = ? AND player_uuid = ? AND claimed = 0
                    """)) {
                up.setLong(1, entry.id());
                up.setString(2, uuid.toString());
                return up.executeUpdate() == 1;
            }
        }).whenComplete((claimed, err) -> {
            if (server == null) return;
            server.execute(() -> {
                if (!Boolean.TRUE.equals(claimed)) {
                    player.sendSystemMessage(ItemCodecUtil.text("Este item ja foi resgatado.", ChatFormatting.YELLOW));
                    return;
                }
                if ("COINS".equals(entry.kind())) {
                    NumismaticsBridge.deposit(uuid, entry.spurs());
                    player.sendSystemMessage(ItemCodecUtil.text(
                            "Resgatado da caixa postal: " + NumismaticsBridge.formatSpurs(entry.spurs()) + "!",
                            ChatFormatting.GREEN
                    ));
                    logAsync(uuid, name, "AH_CLAIM_COINS", entry.spurs(), null, null, "mailbox_id=" + entry.id());
                } else {
                    Optional<ItemStack> stackOpt = ItemCodecUtil.deserialize(entry.itemSnbt(), player.registryAccess());
                    if (stackOpt.isPresent()) {
                        ItemStack s = stackOpt.get();
                        giveItemOrDrop(player, s, s.getCount());
                        player.sendSystemMessage(ItemCodecUtil.text(
                                "Item resgatado: " + s.getCount() + "x " + s.getHoverName().getString(),
                                ChatFormatting.GREEN
                        ));
                    }
                }
                if (onAfterClaim != null) {
                    onAfterClaim.run();
                }
            });
        });
    }

    private void expireStaleListings(Connection conn) throws SQLException {
        List<AhListing> expired = new ArrayList<>();
        try (PreparedStatement sel = conn.prepareStatement("""
                SELECT id, seller_uuid, seller_name, item_id, item_name, item_count, item_snbt, price, expires_at
                FROM cp_ah_listings
                WHERE status = 'ACTIVE' AND expires_at <= NOW()
                LIMIT 50
                """);
             ResultSet rs = sel.executeQuery()) {
            while (rs.next()) {
                expired.add(new AhListing(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        rs.getString("seller_name"),
                        rs.getString("item_id"),
                        rs.getString("item_name"),
                        rs.getInt("item_count"),
                        rs.getString("item_snbt"),
                        rs.getInt("price"),
                        rs.getTimestamp("expires_at").toInstant()
                ));
            }
        }
        for (AhListing listing : expired) {
            try (PreparedStatement up = conn.prepareStatement(
                    "UPDATE cp_ah_listings SET status = 'EXPIRED', closed_at = NOW() WHERE id = ? AND status = 'ACTIVE'")) {
                up.setLong(1, listing.id());
                if (up.executeUpdate() == 1) {
                    try (PreparedStatement mail = conn.prepareStatement("""
                            INSERT INTO cp_ah_mailbox (player_uuid, kind, item_snbt, reason)
                            VALUES (?, 'ITEM', ?, ?)
                            """)) {
                        mail.setString(1, listing.sellerUuid().toString());
                        mail.setString(2, listing.itemSnbt());
                        mail.setString(3, "Anuncio expirado: " + listing.itemCount() + "x " + listing.itemName());
                        mail.executeUpdate();
                    }
                }
            }
        }
    }

    // ==================== AUDIT LOG & HELPERS ====================

    public CompletableFuture<List<String>> getRecentLogs(int limit) {
        int clamped = Math.max(1, Math.min(limit, 30));
        return db.supplyAsync(conn -> {
            List<String> lines = new ArrayList<>();
            String sql = """
                    SELECT actor_name, action, spurs_delta, item_id, item_count, created_at
                    FROM cp_economy_log
                    ORDER BY id DESC
                    LIMIT ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, clamped);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sign = rs.getInt("spurs_delta") >= 0 ? "+" : "";
                        lines.add(String.format("[%s] %s %s (%s%d Spurs) %s x%s",
                                rs.getTimestamp("created_at"),
                                rs.getString("actor_name"),
                                rs.getString("action"),
                                sign,
                                rs.getInt("spurs_delta"),
                                rs.getString("item_id") == null ? "-" : rs.getString("item_id"),
                                rs.getObject("item_count") == null ? "-" : rs.getObject("item_count")
                        ));
                    }
                }
            }
            return lines;
        });
    }

    public void logAsync(UUID actorUuid, String actorName, String action, int spursDelta,
                         String itemId, Integer itemCount, String details) {
        if (!db.isConfigured()) {
            return;
        }
        db.runAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO cp_economy_log (actor_uuid, actor_name, action, spurs_delta, item_id, item_count, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, actorUuid.toString());
                ps.setString(2, actorName);
                ps.setString(3, action);
                ps.setInt(4, spursDelta);
                if (itemId == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, itemId);
                if (itemCount == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, itemCount);
                if (details == null) ps.setNull(7, java.sql.Types.VARCHAR); else ps.setString(7, details);
                ps.executeUpdate();
            }
        });
    }

    public static void giveItemOrDrop(ServerPlayer player, ItemStack template, int count) {
        int remaining = count;
        int maxStack = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            ItemStack stack = template.copy();
            stack.setCount(give);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= give;
        }
    }

    private static List<ItemStack> removeMatchingItems(ServerPlayer player, String targetItemId, int maxCount) {
        List<ItemStack> removed = new ArrayList<>();
        int left = maxCount;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!ItemCodecUtil.itemIdOf(stack).equals(targetItemId)) continue;
            int take = Math.min(left, stack.getCount());
            ItemStack split = stack.split(take);
            removed.add(split);
            left -= take;
        }
        inv.setChanged();
        return removed;
    }

    public record ShopItem(
            int id, String category, String displayName, String itemId, String itemSnbt,
            int amount, Integer buyPrice, Integer sellPrice, int sortOrder
    ) {
    }

    public record DailyContractView(
            int id, String profession, String displayName, String itemId,
            int unitPrice, int dailyCap, int delivered
    ) {
    }

    public record AhListing(
            long id, UUID sellerUuid, String sellerName, String itemId, String itemName,
            int itemCount, String itemSnbt, int price, Instant expiresAt
    ) {
    }

    public record MailboxEntry(
            long id, String kind, int spurs, String itemSnbt, String reason
    ) {
    }
}
