package com.createplus.systems.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 1.21.1 ItemStack <-> SNBT codec (preserves all Data Components) and GUI icon helpers.
 */
public final class ItemCodecUtil {
    private ItemCodecUtil() {
    }

    public static Optional<String> serialize(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                .result()
                .map(Tag::toString);
    }

    public static Optional<ItemStack> deserialize(String snbt, HolderLookup.Provider registries) {
        if (snbt == null || snbt.isBlank()) {
            return Optional.empty();
        }
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                    .result()
                    .filter(s -> !s.isEmpty());
        } catch (CommandSyntaxException ex) {
            return Optional.empty();
        }
    }

    public static ItemStack fromIdOrSnbt(String itemId, String itemSnbt, int count, HolderLookup.Provider registries) {
        if (itemSnbt != null && !itemSnbt.isBlank()) {
            Optional<ItemStack> parsed = deserialize(itemSnbt, registries);
            if (parsed.isPresent()) {
                ItemStack copy = parsed.get().copy();
                if (count > 0) {
                    copy.setCount(count);
                }
                return copy;
            }
        }
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc != null && BuiltInRegistries.ITEM.containsKey(loc)) {
            Item item = BuiltInRegistries.ITEM.get(loc);
            if (item != Items.AIR) {
                return new ItemStack(item, Math.max(1, count));
            }
        }
        return ItemStack.EMPTY;
    }

    public static String itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static ItemStack icon(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        return withDisplay(stack, name, lore);
    }

    public static ItemStack withDisplay(ItemStack base, Component name, List<Component> extraLore) {
        ItemStack copy = base.copy();
        if (name != null) {
            copy.set(DataComponents.CUSTOM_NAME, clean(name));
        }
        if (extraLore != null && !extraLore.isEmpty()) {
            List<Component> merged = new ArrayList<>();
            ItemLore existing = copy.get(DataComponents.LORE);
            if (existing != null) {
                merged.addAll(existing.lines());
            }
            for (Component line : extraLore) {
                merged.add(clean(line));
            }
            copy.set(DataComponents.LORE, new ItemLore(merged));
        }
        return copy;
    }

    public static MutableComponent text(String value, ChatFormatting... styles) {
        MutableComponent c = Component.literal(value).withStyle(style -> style.withItalic(false));
        for (ChatFormatting fmt : styles) {
            c = c.withStyle(fmt);
        }
        return c;
    }

    private static Component clean(Component c) {
        return Component.empty().withStyle(style -> style.withItalic(false)).append(c);
    }
}
