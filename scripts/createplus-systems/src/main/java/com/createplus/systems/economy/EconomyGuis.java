package com.createplus.systems.economy;

import com.createplus.systems.core.ChestGui;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.core.NumismaticsBridge;
import com.createplus.systems.economy.EconomyService.AhListing;
import com.createplus.systems.economy.EconomyService.DailyContractView;
import com.createplus.systems.economy.EconomyService.MailboxEntry;
import com.createplus.systems.economy.EconomyService.ShopItem;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side Chest GUIs for /loja, /vender (+ Black Market Contracts), and /ah (+ My Listings + Mailbox).
 */
public final class EconomyGuis {
    private EconomyGuis() {
    }

    // ==================== /loja ====================

    public static void openShopCategories(ServerPlayer player, EconomyService eco) {
        Map<String, List<ShopItem>> categories = eco.getBuyableCategories();
        ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Loja Oficial — Categorias", ChatFormatting.GOLD, ChatFormatting.BOLD));

        int[] slots = {11, 13, 15, 29, 31, 33};
        int idx = 0;
        for (Map.Entry<String, List<ShopItem>> entry : categories.entrySet()) {
            if (idx >= slots.length) break;
            String catKey = entry.getKey();
            List<ShopItem> items = entry.getValue();
            Item iconItem = categoryIcon(catKey);
            String title = formatCategoryName(catKey);
            ItemStack icon = ItemCodecUtil.icon(
                    iconItem,
                    ItemCodecUtil.text(title, ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    List.of(
                            ItemCodecUtil.text(items.size() + " itens disponiveis", ChatFormatting.GRAY),
                            ItemCodecUtil.text("Clique para abrir", ChatFormatting.GREEN)
                    )
            );
            gui.slot(slots[idx++], icon, p -> openShopCategory(p, eco, catKey, 0));
        }

        if (categories.isEmpty()) {
            gui.slot(22, ItemCodecUtil.icon(
                    Items.BARRIER,
                    ItemCodecUtil.text("Nenhum item cadastrado na loja", ChatFormatting.RED),
                    List.of(ItemCodecUtil.text("Cadastre itens na tabela cp_shop_items", ChatFormatting.GRAY))
            ));
        }

        addBalanceIcon(gui, player, 49);
        gui.slot(45, ItemCodecUtil.icon(
                Items.GOLD_INGOT,
                ItemCodecUtil.text("Ir para /vender (Venda & Contratos)", ChatFormatting.AQUA),
                List.of(ItemCodecUtil.text("Venda drops ou entregue contratos diarios", ChatFormatting.GRAY))
        ), p -> openSellBox(p, eco));
        gui.slot(53, ItemCodecUtil.icon(
                Items.CHEST,
                ItemCodecUtil.text("Ir para /ah (Mercado de Jogadores)", ChatFormatting.LIGHT_PURPLE),
                List.of(ItemCodecUtil.text("Compre e venda com outros jogadores", ChatFormatting.GRAY))
        ), p -> openAuctionHouse(p, eco, 0));
        gui.fillBottomBar();
        gui.open(player);
    }

    public static void openShopCategory(ServerPlayer player, EconomyService eco, String category, int page) {
        List<ShopItem> items = eco.getBuyableCategories().getOrDefault(category, List.of());
        int pageSize = 45;
        int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        ChestGui gui = ChestGui.menu(ItemCodecUtil.text(
                "Loja — " + formatCategoryName(category) + " (" + (safePage + 1) + "/" + totalPages + ")",
                ChatFormatting.GOLD, ChatFormatting.BOLD
        ));

        int start = safePage * pageSize;
        int end = Math.min(items.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            ShopItem item = items.get(i);
            ItemStack raw = ItemCodecUtil.fromIdOrSnbt(item.itemId(), item.itemSnbt(), item.amount(), player.registryAccess());
            if (raw.isEmpty()) {
                raw = new ItemStack(Items.PAPER);
            }
            int price = item.buyPrice() == null ? 0 : item.buyPrice();
            ItemStack display = ItemCodecUtil.withDisplay(
                    raw,
                    ItemCodecUtil.text(item.displayName(), ChatFormatting.YELLOW, ChatFormatting.BOLD),
                    List.of(
                            ItemCodecUtil.text("Quantidade: " + item.amount() + "x", ChatFormatting.GRAY),
                            ItemCodecUtil.text("Preco: " + NumismaticsBridge.formatSpurs(price), ChatFormatting.GOLD),
                            ItemCodecUtil.text("Clique: comprar 1 lote | Shift+Clique: 8 lotes", ChatFormatting.GREEN)
                    )
            );
            gui.slot(i - start, display, (p, clickType) -> {
                int batches = (clickType == ClickType.QUICK_MOVE) ? 8 : 1;
                eco.buyFromShop(p, item, batches);
                openShopCategory(p, eco, category, safePage);
            });
        }

        gui.slot(45, ItemCodecUtil.icon(
                Items.ARROW,
                ItemCodecUtil.text("Voltar as Categorias", ChatFormatting.YELLOW),
                List.of()
        ), p -> openShopCategories(p, eco));
        if (safePage > 0) {
            gui.slot(48, ItemCodecUtil.icon(Items.SPECTRAL_ARROW, ItemCodecUtil.text("Pagina Anterior", ChatFormatting.AQUA), List.of()),
                    p -> openShopCategory(p, eco, category, safePage - 1));
        }
        addBalanceIcon(gui, player, 49);
        if (safePage + 1 < totalPages) {
            gui.slot(50, ItemCodecUtil.icon(Items.SPECTRAL_ARROW, ItemCodecUtil.text("Proxima Pagina", ChatFormatting.AQUA), List.of()),
                    p -> openShopCategory(p, eco, category, safePage + 1));
        }
        gui.fillBottomBar();
        gui.open(player);
    }

    // ==================== /vender & BLACK MARKET CONTRACTS ====================

    public static void openSellBox(ServerPlayer player, EconomyService eco) {
        ChestGui gui = ChestGui.depositBox(ItemCodecUtil.text("Caixa de Venda Rapida (/vender)", ChatFormatting.DARK_GREEN, ChatFormatting.BOLD));
        final boolean[] confirmed = {false};

        gui.slot(45, ItemCodecUtil.icon(
                Items.WRITABLE_BOOK,
                ItemCodecUtil.text("Contratos Diarios (Black Market)", ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        ItemCodecUtil.text("Entregue itens das 5 profissoes", ChatFormatting.GRAY),
                        ItemCodecUtil.text("com preco especial e teto diario!", ChatFormatting.GRAY)
                )
        ), p -> {
            returnDepositItems(p, gui);
            confirmed[0] = true;
            openContractsGui(p, eco);
        });

        gui.slot(49, ItemCodecUtil.icon(
                Items.EMERALD_BLOCK,
                ItemCodecUtil.text("CONFIRMAR VENDA", ChatFormatting.GREEN, ChatFormatting.BOLD),
                List.of(
                        ItemCodecUtil.text("Coloque itens nos slots acima e clique aqui.", ChatFormatting.GRAY),
                        ItemCodecUtil.text("Itens nao vendaveis serao devolvidos.", ChatFormatting.YELLOW)
                )
        ), p -> {
            int totalSpurs = 0;
            int totalItems = 0;
            List<ItemStack> unsellable = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                ItemStack stack = gui.container().getItem(i);
                if (stack.isEmpty()) continue;
                int unitPrice = eco.getSellUnitPrice(stack);
                if (unitPrice > 0) {
                    totalSpurs += unitPrice * stack.getCount();
                    totalItems += stack.getCount();
                    gui.container().setItem(i, ItemStack.EMPTY);
                } else {
                    unsellable.add(stack.copy());
                    gui.container().setItem(i, ItemStack.EMPTY);
                }
            }
            for (ItemStack back : unsellable) {
                EconomyService.giveItemOrDrop(p, back, back.getCount());
            }
            if (totalSpurs > 0) {
                eco.creditQuickSell(p, totalSpurs, totalItems);
            } else {
                p.sendSystemMessage(ItemCodecUtil.text(
                        "Nenhum item vendavel na venda livre. Confira os Contratos Diarios (livro a esquerda)!",
                        ChatFormatting.YELLOW
                ));
            }
            confirmed[0] = true;
            p.closeContainer();
        });

        gui.slot(53, ItemCodecUtil.icon(
                Items.BARRIER,
                ItemCodecUtil.text("Cancelar e Devolver Itens", ChatFormatting.RED),
                List.of()
        ), p -> {
            returnDepositItems(p, gui);
            confirmed[0] = true;
            p.closeContainer();
        });

        gui.onClose(p -> {
            if (!confirmed[0]) {
                returnDepositItems(p, gui);
            }
        });
        gui.fillBottomBar();
        gui.open(player);
    }

    private static void returnDepositItems(ServerPlayer player, ChestGui gui) {
        for (int i = 0; i < 45; i++) {
            ItemStack stack = gui.container().getItem(i);
            if (!stack.isEmpty()) {
                gui.container().setItem(i, ItemStack.EMPTY);
                EconomyService.giveItemOrDrop(player, stack, stack.getCount());
            }
        }
    }

    public static void openContractsGui(ServerPlayer player, EconomyService eco) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        eco.getTodayContracts(player.getUUID()).whenComplete((contracts, err) -> server.execute(() -> {
            if (err != null || contracts == null) {
                player.sendSystemMessage(ItemCodecUtil.text("Erro ao carregar contratos diarios do MariaDB.", ChatFormatting.RED));
                return;
            }
            ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Black Market — Contratos Diarios", ChatFormatting.GOLD, ChatFormatting.BOLD));
            int[] slots = {11, 13, 15, 29, 31, 33};
            for (int i = 0; i < contracts.size() && i < slots.length; i++) {
                DailyContractView c = contracts.get(i);
                int remaining = Math.max(0, c.dailyCap() - c.delivered());
                ItemStack base = ItemCodecUtil.fromIdOrSnbt(c.itemId(), null, 1, player.registryAccess());
                if (base.isEmpty()) {
                    base = new ItemStack(Items.PAPER);
                }
                ChatFormatting statusColor = remaining > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                ItemStack display = ItemCodecUtil.withDisplay(
                        base,
                        ItemCodecUtil.text("[" + c.profession() + "] " + c.displayName(), ChatFormatting.YELLOW, ChatFormatting.BOLD),
                        List.of(
                                ItemCodecUtil.text("Item: " + c.itemId(), ChatFormatting.DARK_GRAY),
                                ItemCodecUtil.text("Pagamento: " + NumismaticsBridge.formatSpurs(c.unitPrice()) + " / un.", ChatFormatting.GOLD),
                                ItemCodecUtil.text("Progresso hoje: " + c.delivered() + " / " + c.dailyCap(), statusColor),
                                remaining > 0
                                        ? ItemCodecUtil.text("Clique: entregar 16 | Shift+Clique: entregar tudo", ChatFormatting.AQUA)
                                        : ItemCodecUtil.text("Teto diario concluido!", ChatFormatting.RED)
                        )
                );
                gui.slot(slots[i], display, (p, clickType) -> {
                    int maxDeliver = (clickType == ClickType.QUICK_MOVE) ? remaining : Math.min(16, remaining);
                    eco.deliverToContract(p, c, maxDeliver);
                    p.closeContainer();
                });
            }

            gui.slot(45, ItemCodecUtil.icon(Items.ARROW, ItemCodecUtil.text("Voltar para /vender", ChatFormatting.YELLOW), List.of()),
                    p -> openSellBox(p, eco));
            addBalanceIcon(gui, player, 49);
            gui.fillBottomBar();
            gui.open(player);
        }));
    }

    // ==================== /ah (AUCTION HOUSE) ====================

    public static void openAuctionHouse(ServerPlayer player, EconomyService eco, int page) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        eco.getActiveListings().whenComplete((listings, err) -> server.execute(() -> {
            if (err != null || listings == null) {
                player.sendSystemMessage(ItemCodecUtil.text("Erro ao carregar anuncios do /ah.", ChatFormatting.RED));
                return;
            }
            int pageSize = 45;
            int totalPages = Math.max(1, (listings.size() + pageSize - 1) / pageSize);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));

            ChestGui gui = ChestGui.menu(ItemCodecUtil.text(
                    "Mercado (/ah) — Pagina " + (safePage + 1) + "/" + totalPages,
                    ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD
            ));

            int start = safePage * pageSize;
            int end = Math.min(listings.size(), start + pageSize);
            for (int i = start; i < end; i++) {
                AhListing l = listings.get(i);
                ItemStack raw = ItemCodecUtil.deserialize(l.itemSnbt(), player.registryAccess())
                        .orElseGet(() -> ItemCodecUtil.fromIdOrSnbt(l.itemId(), null, l.itemCount(), player.registryAccess()));
                if (raw.isEmpty()) {
                    raw = new ItemStack(Items.PAPER);
                }
                long hoursLeft = Math.max(0, Duration.between(Instant.now(), l.expiresAt()).toHours());
                ItemStack display = ItemCodecUtil.withDisplay(
                        raw,
                        null,
                        List.of(
                                ItemCodecUtil.text("--------------------------", ChatFormatting.DARK_GRAY),
                                ItemCodecUtil.text("Preco: " + NumismaticsBridge.formatSpurs(l.price()), ChatFormatting.GOLD, ChatFormatting.BOLD),
                                ItemCodecUtil.text("Vendedor: " + l.sellerName(), ChatFormatting.GRAY),
                                ItemCodecUtil.text("Expira em: ~" + hoursLeft + "h", ChatFormatting.DARK_GRAY),
                                ItemCodecUtil.text("Clique para comprar", ChatFormatting.GREEN)
                        )
                );
                gui.slot(i - start, display, p -> openConfirmBuyAh(p, eco, l, safePage));
            }

            if (listings.isEmpty()) {
                gui.slot(22, ItemCodecUtil.icon(
                        Items.PAPER,
                        ItemCodecUtil.text("Nenhum anuncio ativo no momento", ChatFormatting.YELLOW),
                        List.of(ItemCodecUtil.text("Segure um item e use /ah vender <preco>", ChatFormatting.GRAY))
                ));
            }

            gui.slot(45, ItemCodecUtil.icon(
                    Items.CHEST,
                    ItemCodecUtil.text("Meus Anuncios", ChatFormatting.YELLOW),
                    List.of(ItemCodecUtil.text("Veja ou cancele seus anuncios ativos", ChatFormatting.GRAY))
            ), p -> openMyListings(p, eco));
            gui.slot(46, ItemCodecUtil.icon(
                    Items.ENDER_CHEST,
                    ItemCodecUtil.text("Caixa Postal (/ah caixa)", ChatFormatting.AQUA),
                    List.of(ItemCodecUtil.text("Resgate moedas de vendas e itens expirados", ChatFormatting.GRAY))
            ), p -> openMailbox(p, eco));
            if (safePage > 0) {
                gui.slot(48, ItemCodecUtil.icon(Items.ARROW, ItemCodecUtil.text("Pagina Anterior", ChatFormatting.YELLOW), List.of()),
                        p -> openAuctionHouse(p, eco, safePage - 1));
            }
            addBalanceIcon(gui, player, 49);
            if (safePage + 1 < totalPages) {
                gui.slot(50, ItemCodecUtil.icon(Items.ARROW, ItemCodecUtil.text("Proxima Pagina", ChatFormatting.YELLOW), List.of()),
                        p -> openAuctionHouse(p, eco, safePage + 1));
            }
            gui.slot(53, ItemCodecUtil.icon(
                    Items.EMERALD,
                    ItemCodecUtil.text("Ir para /loja", ChatFormatting.GREEN),
                    List.of()
            ), p -> openShopCategories(p, eco));
            gui.fillBottomBar();
            gui.open(player);
        }));
    }

    private static void openConfirmBuyAh(ServerPlayer player, EconomyService eco, AhListing listing, int returnPage) {
        ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Confirmar Compra no /ah", ChatFormatting.GOLD, ChatFormatting.BOLD));
        ItemStack raw = ItemCodecUtil.deserialize(listing.itemSnbt(), player.registryAccess())
                .orElseGet(() -> ItemCodecUtil.fromIdOrSnbt(listing.itemId(), null, listing.itemCount(), player.registryAccess()));
        if (raw.isEmpty()) raw = new ItemStack(Items.PAPER);

        gui.slot(13, raw);
        gui.slot(29, ItemCodecUtil.icon(
                Items.LIME_WOOL,
                ItemCodecUtil.text("CONFIRMAR COMPRA", ChatFormatting.GREEN, ChatFormatting.BOLD),
                List.of(
                        ItemCodecUtil.text("Pagar: " + NumismaticsBridge.formatSpurs(listing.price()), ChatFormatting.GOLD),
                        ItemCodecUtil.text("Vendedor: " + listing.sellerName(), ChatFormatting.GRAY)
                )
        ), p -> {
            p.closeContainer();
            eco.buyListing(p, listing);
        });
        gui.slot(33, ItemCodecUtil.icon(
                Items.RED_WOOL,
                ItemCodecUtil.text("CANCELAR", ChatFormatting.RED, ChatFormatting.BOLD),
                List.of()
        ), p -> openAuctionHouse(p, eco, returnPage));
        addBalanceIcon(gui, player, 49);
        gui.fillBottomBar();
        gui.open(player);
    }

    public static void openMyListings(ServerPlayer player, EconomyService eco) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        eco.getPlayerListings(player.getUUID()).whenComplete((listings, err) -> server.execute(() -> {
            if (err != null || listings == null) {
                player.sendSystemMessage(ItemCodecUtil.text("Erro ao carregar seus anuncios.", ChatFormatting.RED));
                return;
            }
            ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Meus Anuncios no /ah", ChatFormatting.YELLOW, ChatFormatting.BOLD));
            for (int i = 0; i < Math.min(45, listings.size()); i++) {
                AhListing l = listings.get(i);
                ItemStack raw = ItemCodecUtil.deserialize(l.itemSnbt(), player.registryAccess())
                        .orElseGet(() -> ItemCodecUtil.fromIdOrSnbt(l.itemId(), null, l.itemCount(), player.registryAccess()));
                if (raw.isEmpty()) raw = new ItemStack(Items.PAPER);
                ItemStack display = ItemCodecUtil.withDisplay(
                        raw,
                        null,
                        List.of(
                                ItemCodecUtil.text("Preco: " + NumismaticsBridge.formatSpurs(l.price()), ChatFormatting.GOLD),
                                ItemCodecUtil.text("Clique para CANCELAR anuncio", ChatFormatting.RED)
                        )
                );
                gui.slot(i, display, p -> {
                    p.closeContainer();
                    eco.cancelListing(p, l);
                });
            }
            gui.slot(45, ItemCodecUtil.icon(Items.ARROW, ItemCodecUtil.text("Voltar ao /ah", ChatFormatting.YELLOW), List.of()),
                    p -> openAuctionHouse(p, eco, 0));
            addBalanceIcon(gui, player, 49);
            gui.fillBottomBar();
            gui.open(player);
        }));
    }

    public static void openMailbox(ServerPlayer player, EconomyService eco) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        eco.getUnclaimedMailbox(player.getUUID()).whenComplete((entries, err) -> server.execute(() -> {
            if (err != null || entries == null) {
                player.sendSystemMessage(ItemCodecUtil.text("Erro ao carregar caixa postal.", ChatFormatting.RED));
                return;
            }
            ChestGui gui = ChestGui.menu(ItemCodecUtil.text("Caixa Postal (/ah caixa)", ChatFormatting.AQUA, ChatFormatting.BOLD));
            for (int i = 0; i < Math.min(45, entries.size()); i++) {
                MailboxEntry entry = entries.get(i);
                ItemStack display;
                if ("COINS".equals(entry.kind())) {
                    display = ItemCodecUtil.icon(
                            Items.GOLD_NUGGET,
                            ItemCodecUtil.text(NumismaticsBridge.formatSpurs(entry.spurs()), ChatFormatting.GOLD, ChatFormatting.BOLD),
                            List.of(
                                    ItemCodecUtil.text(entry.reason(), ChatFormatting.GRAY),
                                    ItemCodecUtil.text("Clique para resgatar para sua conta", ChatFormatting.GREEN)
                            )
                    );
                } else {
                    Optional<ItemStack> parsed = ItemCodecUtil.deserialize(entry.itemSnbt(), player.registryAccess());
                    ItemStack base = parsed.orElseGet(() -> new ItemStack(Items.CHEST));
                    display = ItemCodecUtil.withDisplay(
                            base,
                            null,
                            List.of(
                                    ItemCodecUtil.text(entry.reason(), ChatFormatting.GRAY),
                                    ItemCodecUtil.text("Clique para resgatar item", ChatFormatting.GREEN)
                            )
                    );
                }
                gui.slot(i, display, p -> eco.claimMailboxEntry(p, entry, () -> openMailbox(p, eco)));
            }
            if (entries.isEmpty()) {
                gui.slot(22, ItemCodecUtil.icon(
                        Items.PAPER,
                        ItemCodecUtil.text("Caixa postal vazia", ChatFormatting.GRAY),
                        List.of(ItemCodecUtil.text("Vendas offline e itens expirados aparecem aqui.", ChatFormatting.DARK_GRAY))
                ));
            }
            gui.slot(45, ItemCodecUtil.icon(Items.ARROW, ItemCodecUtil.text("Voltar ao /ah", ChatFormatting.YELLOW), List.of()),
                    p -> openAuctionHouse(p, eco, 0));
            addBalanceIcon(gui, player, 49);
            gui.fillBottomBar();
            gui.open(player);
        }));
    }

    private static void addBalanceIcon(ChestGui gui, ServerPlayer player, int slot) {
        int balance = NumismaticsBridge.getBalance(player.getUUID());
        gui.slot(slot, ItemCodecUtil.icon(
                Items.SUNFLOWER,
                ItemCodecUtil.text("Seu Saldo (Numismatics)", ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(ItemCodecUtil.text(NumismaticsBridge.formatSpurs(balance), ChatFormatting.YELLOW))
        ));
    }

    private static Item categoryIcon(String key) {
        return switch (key.toLowerCase()) {
            case "chaves", "dungeons" -> Items.TRIAL_KEY;
            case "create", "engenharia" -> Items.IRON_INGOT;
            case "utilidades" -> Items.ENDER_PEARL;
            case "fragmentos", "especiais" -> Items.NETHER_STAR;
            default -> Items.EMERALD;
        };
    }

    private static String formatCategoryName(String key) {
        return switch (key.toLowerCase()) {
            case "chaves" -> "Chaves de Dungeon";
            case "create" -> "Create & Logistica";
            case "utilidades" -> "Utilidades";
            case "fragmentos" -> "Troca de Fragmentos";
            case "especiais" -> "Especiais / Temporada";
            default -> key.isEmpty() ? "Geral" : Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }
}
