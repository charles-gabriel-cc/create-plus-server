package com.createplus.systems.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reusable server-side 54-slot Chest GUI (works on vanilla/NeoForge clients with zero client mods).
 * Supports both read-only menu mode and interactive deposit-box mode (slots 0..44 editable, 45..53 locked).
 */
public final class ChestGui {
    public static final int ROWS = 6;
    public static final int SIZE = ROWS * 9;

    private final Component title;
    private final boolean depositBoxMode;
    private final SimpleContainer container = new SimpleContainer(SIZE);
    private final Map<Integer, BiConsumer<ServerPlayer, ClickType>> handlers = new HashMap<>();
    private Consumer<ServerPlayer> onClose;

    private ChestGui(Component title, boolean depositBoxMode) {
        this.title = title;
        this.depositBoxMode = depositBoxMode;
    }

    public static ChestGui menu(Component title) {
        return new ChestGui(title, false);
    }

    public static ChestGui depositBox(Component title) {
        return new ChestGui(title, true);
    }

    public SimpleContainer container() {
        return container;
    }

    public ChestGui slot(int index, ItemStack icon, BiConsumer<ServerPlayer, ClickType> onClick) {
        if (index >= 0 && index < SIZE) {
            container.setItem(index, icon);
            if (onClick != null) {
                handlers.put(index, onClick);
            } else {
                handlers.remove(index);
            }
        }
        return this;
    }

    public ChestGui slot(int index, ItemStack icon, Consumer<ServerPlayer> onClick) {
        return slot(index, icon, onClick == null ? null : (player, type) -> onClick.accept(player));
    }

    public ChestGui slot(int index, ItemStack icon) {
        return slot(index, icon, (BiConsumer<ServerPlayer, ClickType>) null);
    }

    public ChestGui fillBottomBar() {
        ItemStack pane = ItemCodecUtil.icon(Items.GRAY_STAINED_GLASS_PANE, ItemCodecUtil.text(" "), List.of());
        for (int i = 45; i < SIZE; i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, pane.copy());
            }
        }
        return this;
    }

    public ChestGui onClose(Consumer<ServerPlayer> callback) {
        this.onClose = callback;
        return this;
    }

    public void open(ServerPlayer player) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player p) {
                return new ManagedChestMenu(containerId, playerInventory, container, ChestGui.this);
            }
        });
    }

    private static final class ManagedChestMenu extends ChestMenu {
        private final ChestGui gui;

        ManagedChestMenu(int containerId, Inventory playerInventory, Container container, ChestGui gui) {
            super(MenuType.GENERIC_9x6, containerId, playerInventory, container, ROWS);
            this.gui = gui;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (!(player instanceof ServerPlayer sp)) {
                return;
            }
            if (slotId >= 0 && slotId < SIZE) {
                if (gui.depositBoxMode && slotId < 45) {
                    super.clicked(slotId, button, clickType, player);
                    return;
                }
                BiConsumer<ServerPlayer, ClickType> handler = gui.handlers.get(slotId);
                if (handler != null) {
                    handler.accept(sp, clickType);
                }
                this.broadcastFullState();
                return;
            }
            if (!gui.depositBoxMode) {
                // Prevent players from accidentally moving items into read-only menus
                if (clickType == ClickType.QUICK_MOVE || clickType == ClickType.PICKUP_ALL) {
                    this.broadcastFullState();
                    return;
                }
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (!gui.depositBoxMode) {
                return ItemStack.EMPTY;
            }
            if (index >= 45 && index < SIZE) {
                return ItemStack.EMPTY;
            }
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return ItemStack.EMPTY;
            }
            ItemStack raw = slot.getItem();
            ItemStack copy = raw.copy();
            if (index < 45) {
                if (!this.moveItemStackTo(raw, SIZE, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(raw, 0, 45, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (raw.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            if (!gui.depositBoxMode) {
                return false;
            }
            return slot.index < 45 || slot.index >= SIZE;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (player instanceof ServerPlayer sp && gui.onClose != null) {
                gui.onClose.accept(sp);
            }
        }
    }
}
