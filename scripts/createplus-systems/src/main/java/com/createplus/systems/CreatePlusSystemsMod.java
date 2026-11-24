package com.createplus.systems;

import com.createplus.systems.core.DatabaseManager;
import com.createplus.systems.core.ItemCodecUtil;
import com.createplus.systems.dungeons.DungeonCommands;
import com.createplus.systems.dungeons.DungeonManager;
import com.createplus.systems.economy.EconomyCommands;
import com.createplus.systems.economy.EconomyService;
import com.createplus.systems.events.EventCommands;
import com.createplus.systems.events.EventManager;
import com.createplus.systems.professions.ProfessionCommands;
import com.createplus.systems.professions.ProfessionManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(CreatePlusSystemsMod.MOD_ID)
public final class CreatePlusSystemsMod {
    public static final String MOD_ID = "createplussystems";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final DatabaseManager database = new DatabaseManager();
    private final EconomyService economy = new EconomyService(database);
    private final DungeonManager dungeons = new DungeonManager(database, economy);
    private final EventManager events = new EventManager(database, economy);
    private final ProfessionManager professions = new ProfessionManager(database);

    public CreatePlusSystemsMod(IEventBus modEventBus) {
        dungeons.setEventManager(events);

        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        // Dungeon protection + Event mining competition
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);

        // Events & Professions combat/RP hooks
        NeoForge.EVENT_BUS.addListener(this::onLivingDamaged);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(this::onItemFished);
        NeoForge.EVENT_BUS.addListener(this::onAdvancement);

        // Profession workstation/tool Tier gating (Albion-style: finished products bought on /ah are freely usable)
        NeoForge.EVENT_BUS.addListener(this::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path serverRoot = event.getServer().getServerDirectory();
        boolean dbReady = this.database.loadConfig(serverRoot);

        this.dungeons.initialize(serverRoot);
        this.professions.initialize(serverRoot);
        if (dbReady) {
            this.economy.initialize().whenComplete((v, err) -> {
                if (err != null) LOGGER.error("Failed to initialize Economy MariaDB schema/cache.", err);
            });
            this.events.initialize().whenComplete((v, err) -> {
                if (err != null) LOGGER.error("Failed to initialize Events MariaDB schema/schedule.", err);
            });
            LOGGER.info("Create+ Systems (Economy, Dungeons, Events, Professions) initialized.");
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        EconomyCommands.register(event.getDispatcher(), this.economy);
        DungeonCommands.register(event.getDispatcher(), this.dungeons);
        EventCommands.register(event.getDispatcher(), this.events);
        ProfessionCommands.register(event.getDispatcher(), this.professions);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        this.dungeons.shutdownAll(event.getServer());
        this.database.close();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        this.dungeons.tick(event.getServer());
        this.events.tick(event.getServer());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            this.dungeons.onPlayerLogin(player);
            this.events.onPlayerJoin(player);
            this.professions.onPlayerLogin(player);
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            this.professions.onPlayerLogout(player.getUUID());
        }
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (dungeons.isInDungeonDimension(player.serverLevel()) && !player.isCreative()) {
            event.setCanceled(true);
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString();
        if (blockId.contains("_ore") || blockId.contains("ancient_debris")) {
            events.onCompetitionAction(player, "MINING", 1.0);
        }
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (dungeons.isInDungeonDimension(player.serverLevel()) && !player.isCreative()) {
                event.setCanceled(true);
                return;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
            if (!professions.checkAccess(player, blockId, "WORKSTATION")) {
                event.setCanceled(true);
            }
        }
    }

    private void onLivingDamaged(LivingDamageEvent.Post event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            events.onEntityDamaged(event.getEntity(), attacker, event.getNewDamage());
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
            if (victim instanceof Enemy) {
                events.onCompetitionAction(killer, "KILLS", 1.0);
            }
            professions.onMobKilled(killer, entityId);
        }
    }

    private void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            professions.onItemCrafted(player, event.getCrafting());
        }
    }

    private void onItemFished(ItemFishedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            events.onCompetitionAction(player, "FISHING", Math.max(1, event.getDrops().size()));
            professions.onFishCaught(player, event.getDrops());
        }
    }

    private void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            professions.onAdvancement(player, event.getAdvancement().id().toString());
        }
    }

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ItemStack stack = event.getItemStack();
            if (!stack.isEmpty() && !professions.checkAccess(player, ItemCodecUtil.itemIdOf(stack), "TOOL")) {
                event.setCanceled(true);
            }
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ItemStack stack = event.getItemStack();
            if (!stack.isEmpty() && !professions.checkAccess(player, ItemCodecUtil.itemIdOf(stack), "TOOL")) {
                event.setCanceled(true);
                return;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(event.getLevel().getBlockState(event.getPos()).getBlock()).toString();
            if (!professions.checkAccess(player, blockId, "WORKSTATION")) {
                event.setCanceled(true);
            }
        }
    }
}
