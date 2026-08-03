package pl.bananek355.getDzialki;

import org.bukkit.entity.Player;
import pl.komentarz.sTORMANTYLOGOUT.StormAntiLogout;

/**
 * Cienka warstwa integracji z pluginem STORMANTYLOGOUT.
 *
 * WAŻNE: ta klasa jest celowo wydzielona osobno. JVM weryfikuje/ładuje klasę
 * dopiero gdy faktycznie wywołasz na niej metodę - dopóki GetDzialkiPlugin
 * sam sprawdza (przez Bukkit.getPluginManager().getPlugin("STORMANTYLOGOUT") != null)
 * czy Storm jest w ogóle zainstalowany zanim wywoła metody z tej klasy,
 * brak pliku STORMANTYLOGOUT.jar na serwerze NIE spowoduje błędu przy starcie
 * GetDzialki (softdepend, nie hard-depend).
 */
public final class StormIntegration {

    private StormIntegration() {}

    /**
     * Czy gracz jest aktualnie w stanie "antylogout" (czyli w praktyce: w trakcie walki PvP)
     * wg pluginu STORMANTYLOGOUT.
     */
    public static boolean isInCombat(Player player) {
        StormAntiLogout instance = StormAntiLogout.getInstance();
        if (instance == null || instance.getAntiLogoutManager() == null) return false;
        return instance.getAntiLogoutManager().isInAntiLogout(player);
    }

    /**
     * Czy gracz może obejść mechanizm antylogout (op / permission bypass) wg configu Storma.
     * Jeśli tak, GetDzialki też nie powinno go blokować glasswallem.
     */
    public static boolean canBypass(Player player) {
        StormAntiLogout instance = StormAntiLogout.getInstance();
        if (instance == null || instance.getAntiLogoutManager() == null) return false;
        return instance.getAntiLogoutManager().canBypassAntiLogout(player, false);
    }

    /**
     * Ile sekund pozostało graczowi w stanie walki (do celów informacyjnych w wiadomościach).
     */
    public static int getRemainingCombatTime(Player player) {
        StormAntiLogout instance = StormAntiLogout.getInstance();
        if (instance == null || instance.getAntiLogoutManager() == null) return 0;
        return instance.getAntiLogoutManager().getRemainingTime(player);
    }
}
