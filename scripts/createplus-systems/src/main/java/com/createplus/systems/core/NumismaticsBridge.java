package com.createplus.systems.core;

import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.Coin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct server-thread bridge to Create: Numismatics player bank accounts.
 * Base currency unit is Spurs (1 Bevel = 8, 1 Sprocket = 16, 1 Cog = 64, 1 Crown = 512, 1 Sun = 4096).
 * Must only be invoked on the main Minecraft server thread.
 */
public final class NumismaticsBridge {
    private NumismaticsBridge() {
    }

    public static BankAccount account(UUID playerId) {
        return Numismatics.BANK.getOrCreateAccount(playerId, BankAccount.Type.PLAYER);
    }

    public static int getBalance(UUID playerId) {
        return account(playerId).getBalance();
    }

    public static boolean hasBalance(UUID playerId, int spurs) {
        if (spurs <= 0) {
            return true;
        }
        return getBalance(playerId) >= spurs;
    }

    public static boolean deduct(UUID playerId, int spurs) {
        if (spurs <= 0) {
            return true;
        }
        BankAccount acc = account(playerId);
        return acc.deduct(spurs, false);
    }

    public static void deposit(UUID playerId, int spurs) {
        if (spurs <= 0) {
            return;
        }
        account(playerId).deposit(spurs);
    }

    /**
     * Formats an amount in Spurs into a readable Numismatics coin breakdown, e.g.:
     * "2 Crowns, 3 Cogs (1.216 Spurs)" or "45 Spurs".
     */
    public static String formatSpurs(int spurs) {
        if (spurs <= 0) {
            return "0 Spurs";
        }
        List<Map.Entry<Coin, Integer>> breakdown = Coin.getCoinsFromSpurAmount(spurs);
        if (breakdown.isEmpty()) {
            return spurs + " Spurs";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Coin, Integer> entry : breakdown) {
            int qty = entry.getValue();
            if (qty <= 0) {
                continue;
            }
            Coin coin = entry.getKey();
            String label = coin.getName(qty);
            parts.add(qty + " " + capitalize(label));
        }
        String joined = String.join(", ", parts);
        if (breakdown.size() == 1 && breakdown.get(0).getKey() == Coin.SPUR) {
            return joined;
        }
        return joined + " (" + spurs + " Spurs)";
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
