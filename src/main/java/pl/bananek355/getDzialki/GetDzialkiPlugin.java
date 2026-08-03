package pl.bananek355.getDzialki;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Animals;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

public class GetDzialkiPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private File messagesFile;
    private FileConfiguration messagesConfig;
    private Connection dbConnection;
    private Economy econ = null;
    private NamespacedKey recipeKey;

    private final Map<UUID, PlotData> plotsByOwner = new HashMap<>();
    private final List<PlotData> allPlots = new ArrayList<>();
    private final Map<UUID, Set<UUID>> plotMembers = new HashMap<>();
    private final Map<UUID, List<TextDisplay>> plotHolograms = new HashMap<>();
    private int hologramValidatorTask = -1;
    private final Map<UUID, PlotData> playerCurrentPlot = new HashMap<>();
    private final Map<UUID, PlotData> pendingDelete = new HashMap<>();
    private NamespacedKey hologramTagKey;

    public static class PlotData {
        public UUID id;
        public UUID owner;
        public String ownerName;
        public String world;
        public int centerX, centerY, centerZ;
        public int level;
        public int maxMembersLevel; 
        public String createdAt;
        public boolean fall, fire, explode, pvp, liquids;

        public PlotData(UUID id, UUID owner, String ownerName, String world, int centerX, int centerY, int centerZ, int level, int maxMembersLevel, String createdAt) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.level = level;
            this.maxMembersLevel = maxMembersLevel;
            this.createdAt = createdAt;
            this.fall = true;
            this.fire = true;
            this.explode = false;
            this.pvp = true;
            this.liquids = false;
        }

        public int getSize() {
            return 16 + (level * 4);
        }

        public int getMaxMembers() {
            return 2 + (maxMembersLevel * 2);
        }

        public boolean isInPlot(Location loc) {
            if (!loc.getWorld().getName().equals(world)) return false;
            int radius = getSize() / 2;
            int minX = centerX - radius;
            int maxX = centerX + radius - 1;
            int minZ = centerZ - radius;
            int maxZ = centerZ + radius - 1;
            return loc.getBlockX() >= minX && loc.getBlockX() <= maxX && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
        }

        public boolean overlaps(String w, int cx, int cz, int lvl) {
            if (!w.equals(world)) return false;
            int r1 = (16 + (lvl * 4)) / 2;
            int r2 = getSize() / 2;
            
            int minX1 = cx - r1;
            int maxX1 = cx + r1 - 1;
            int minZ1 = cz - r1;
            int maxZ1 = cz + r1 - 1;

            int minX2 = centerX - r2;
            int maxX2 = centerX + r2 - 1;
            int minZ2 = centerZ - r2;
            int maxZ2 = centerZ + r2 - 1;

            return !(maxX1 < minX2 || minX1 > maxX2 || maxZ1 < minZ2 || minZ1 > maxZ2);
        }
    }

    @Override
    public void onEnable() {
        this.recipeKey = new NamespacedKey(this, "dzialka_block");
        this.hologramTagKey = new NamespacedKey(this, "getdzialki_hologram");
        saveDefaultConfig();
        createMessagesConfig();
        setupEconomy();
        initDatabase();
        loadData();
        getServer().getScheduler().runTaskLater(this, this::refreshAllHolograms, 20L);
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("dzialka").setExecutor(this);
        getCommand("dzialka").setTabCompleter(this);
        getCommand("getdzialka").setExecutor(this);
        getCommand("getdzialka").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        removeAllHolograms();
        try {
            if (getServer().getRecipe(recipeKey) != null) {
                getServer().removeRecipe(recipeKey);
            }
        } catch (Exception ignored) {}
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    private void createMessagesConfig() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMsg(String path) {
        String prefix = messagesConfig.getString("prefix", "");
        String msg = messagesConfig.getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    public String getRawMsg(String path) {
        return ChatColor.translateAlternateColorCodes('&', messagesConfig.getString(path, ""));
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            File dbFile = new File(getDataFolder(), "data.db");
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS plots (id TEXT PRIMARY KEY, owner TEXT, owner_name TEXT, world TEXT, cx INTEGER, cy INTEGER, cz INTEGER, level INTEGER, members_level INTEGER, created_at TEXT, fall INTEGER, fire INTEGER, explode INTEGER, pvp INTEGER, liquids INTEGER);");
                stmt.execute("CREATE TABLE IF NOT EXISTS members (owner TEXT, member TEXT);");
                try {
                    stmt.execute("ALTER TABLE plots ADD COLUMN liquids INTEGER DEFAULT 0;");
                } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        try (Statement stmt = dbConnection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM plots");
            while (rs.next()) {
                String oName = rs.getString("owner_name");
                if (oName == null) oName = "Nieznany";
                String cAt = rs.getString("created_at");
                if (cAt == null) cAt = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date());

                PlotData plot = new PlotData(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("owner")),
                        oName,
                        rs.getString("world"),
                        rs.getInt("cx"),
                        rs.getInt("cy"),
                        rs.getInt("cz"),
                        rs.getInt("level"),
                        rs.getInt("members_level"),
                        cAt
                );
                plot.fall = rs.getInt("fall") == 1;
                plot.fire = rs.getInt("fire") == 1;
                plot.explode = rs.getInt("explode") == 1;
                plot.pvp = rs.getInt("pvp") == 1;
                plot.liquids = rs.getInt("liquids") == 1;
                
                plotsByOwner.put(plot.owner, plot);
                allPlots.add(plot);
            }
            ResultSet rsMembers = stmt.executeQuery("SELECT * FROM members");
            while (rsMembers.next()) {
                UUID owner = UUID.fromString(rsMembers.getString("owner"));
                UUID member = UUID.fromString(rsMembers.getString("member"));
                plotMembers.computeIfAbsent(owner, k -> new HashSet<>()).add(member);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void removeAllHolograms() {
        // Zatrzymaj validator task
        if (hologramValidatorTask != -1) {
            Bukkit.getScheduler().cancelTask(hologramValidatorTask);
            hologramValidatorTask = -1;
        }
        // Usuń hologramy śledzone w pamięci
        for (List<TextDisplay> displays : plotHolograms.values()) {
            for (TextDisplay td : displays) {
                if (td.isValid()) td.remove();
            }
        }
        plotHolograms.clear();

        // Przeskanuj wszystkie światy i usuń TextDisplay z naszym tagiem PDC
        // (po /reload lub restarcie, gdy mapa w pamięci była pusta)
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay td : world.getEntitiesByClass(TextDisplay.class)) {
                if (td.getPersistentDataContainer().has(hologramTagKey, PersistentDataType.BYTE)) {
                    td.remove();
                }
            }
        }
    }

    private void refreshAllHolograms() {
        removeAllHolograms();
        for (PlotData plot : allPlots) {
            createHologram(plot);
        }
        startHologramValidator();
    }

    private void createHologram(PlotData plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) return;

        // Sprawdź czy blok działki tam jest
        Location blockLoc = new Location(world, plot.centerX, plot.centerY, plot.centerZ);
        String dzialkaBlockMat = getConfig().getString("block-type", "NOTE_BLOCK");
        Material dzialkaBlockType = Material.matchMaterial(dzialkaBlockMat);
        if (dzialkaBlockType == null) dzialkaBlockType = Material.NOTE_BLOCK;
        if (blockLoc.getBlock().getType() != dzialkaBlockType) return;

        Location baseLoc = new Location(world, plot.centerX + 0.5, plot.centerY + 1.5, plot.centerZ + 0.5);

        List<String> lines = getConfig().getStringList("hologram-lines");
        List<TextDisplay> displays = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i)
                    .replace("{owner}", plot.ownerName)
                    .replace("{size}", String.valueOf(plot.getSize()))
                    .replace("{created_at}", plot.createdAt);
            final String coloredLine = ChatColor.translateAlternateColorCodes('&', line);

            Location lineLoc = baseLoc.clone().add(0, i * 0.3, 0);
            TextDisplay td = world.spawn(lineLoc, TextDisplay.class, display -> {
                display.setText(coloredLine);
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setShadowed(true);
                display.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(1f, 1f, 1f),
                        new AxisAngle4f(0, 0, 0, 1)
                ));
                display.setInvulnerable(true);
                display.getPersistentDataContainer().set(hologramTagKey, PersistentDataType.BYTE, (byte) 1);
                // Zapisz UUID właściciela działki w PDC żeby validator wiedział do kogo należy
                display.getPersistentDataContainer().set(
                        new NamespacedKey(this, "getdzialki_owner"),
                        PersistentDataType.STRING,
                        plot.owner.toString()
                );
            });
            displays.add(td);
        }
        plotHolograms.put(plot.id, displays);
    }

    private void removeHologram(PlotData plot) {
        List<TextDisplay> displays = plotHolograms.remove(plot.id);
        if (displays != null) {
            for (TextDisplay td : displays) {
                if (td.isValid()) td.remove();
            }
        }
    }

    private void startHologramValidator() {
        if (hologramValidatorTask != -1) {
            Bukkit.getScheduler().cancelTask(hologramValidatorTask);
        }
        hologramValidatorTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            String dzialkaBlockMat = getConfig().getString("block-type", "NOTE_BLOCK");
            Material dzialkaBlockType = Material.matchMaterial(dzialkaBlockMat);
            if (dzialkaBlockType == null) dzialkaBlockType = Material.NOTE_BLOCK;
            final Material finalBlockType = dzialkaBlockType;

            // Krok 1: usuń zbłąkane TextDisplay (bez działki lub z błędnym PDC)
            NamespacedKey ownerKey = new NamespacedKey(this, "getdzialki_owner");
            for (World world : Bukkit.getWorlds()) {
                for (TextDisplay td : world.getEntitiesByClass(TextDisplay.class)) {
                    PersistentDataContainer pdc = td.getPersistentDataContainer();
                    if (!pdc.has(hologramTagKey, PersistentDataType.BYTE)) continue;

                    String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
                    if (ownerStr == null) { td.remove(); continue; }

                    UUID ownerUUID;
                    try { ownerUUID = UUID.fromString(ownerStr); }
                    catch (IllegalArgumentException e) { td.remove(); continue; }

                    // Jeśli działka nie istnieje → usuń hologram
                    PlotData plot = plotsByOwner.get(ownerUUID);
                    if (plot == null) { td.remove(); continue; }

                    // Jeśli blok działki zniknął → usuń hologram (zostanie odtworzony w Kroku 2)
                    World plotWorld = Bukkit.getWorld(plot.world);
                    if (plotWorld == null) { td.remove(); continue; }
                    Block block = plotWorld.getBlockAt(plot.centerX, plot.centerY, plot.centerZ);
                    if (block.getType() != finalBlockType) {
                        td.remove();
                    }
                }
            }

            // Krok 2: dla każdej działki sprawdź czy ma hologram i czy blok jest — jeśli nie, stwórz nowy
            for (PlotData plot : allPlots) {
                World plotWorld = Bukkit.getWorld(plot.world);
                if (plotWorld == null) continue;

                Block block = plotWorld.getBlockAt(plot.centerX, plot.centerY, plot.centerZ);
                if (block.getType() != finalBlockType) continue; // bloku nie ma, hologram niepotrzebny

                List<TextDisplay> existing = plotHolograms.get(plot.id);
                boolean needsRespawn = (existing == null || existing.isEmpty());
                if (!needsRespawn) {
                    // Sprawdź czy wszystkie encje nadal żyją
                    boolean allValid = existing.stream().allMatch(td -> td != null && td.isValid());
                    if (!allValid) needsRespawn = true;
                }

                if (needsRespawn) {
                    // Usuń stare (na wypadek częściowych)
                    if (existing != null) {
                        existing.forEach(td -> { if (td != null && td.isValid()) td.remove(); });
                    }
                    plotHolograms.remove(plot.id);
                    createHologram(plot);
                }
            }
        }, 100L, 100L); // co 5 sekund
    }

    private ItemStack getDzialkaBlock() {
        String materialName = getConfig().getString("block-type", "NOTE_BLOCK");
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) mat = Material.NOTE_BLOCK;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("block-name", "&aBlok Działki")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("block-lore")) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerRecipe() {
        try {
            if (getServer().getRecipe(recipeKey) != null) {
                getServer().removeRecipe(recipeKey);
            }
        } catch (Exception ignored) {}

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, getDzialkaBlock());
        recipe.shape("WWW", "WNW", "III");
        recipe.setIngredient('W', Material.OAK_PLANKS);
        
        String materialName = getConfig().getString("block-type", "NOTE_BLOCK");
        Material centerMat = Material.matchMaterial(materialName);
        if (centerMat == null) centerMat = Material.NOTE_BLOCK;
        recipe.setIngredient('N', centerMat);
        
        recipe.setIngredient('I', Material.IRON_INGOT);
        
        // ZABEZPIECZENIE PRZED DUPLIKATEM RECEPTURY (Paper 1.21+ / PlugMan)
        try {
            getServer().addRecipe(recipe);
        } catch (IllegalStateException e) {
            // Receptura już istnieje w rejestrze gry, ignorujemy błąd bez przerywania onEnable
        }
    }

    private boolean canBuildWG(Player player, Location loc) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        return query.testState(BukkitAdapter.adapt(loc), WorldGuardPlugin.inst().wrapPlayer(player), Flags.BUILD);
    }

    private PlotData getPlotAt(Location loc) {
        for (PlotData plot : allPlots) {
            if (plot.isInPlot(loc)) return plot;
        }
        return null;
    }

    private boolean checkOverlap(String world, int cx, int cz, int lvl, UUID exceptionOwner) {
        for (PlotData plot : allPlots) {
            if (plot.owner.equals(exceptionOwner)) continue;
            if (plot.overlaps(world, cx, cz, lvl)) return true;
        }
        return false;
    }

    private void setFillerItems(Inventory gui, String guiKey, int size) {
        String fillMatStr = getConfig().getString("gui." + guiKey + ".filler.item", "GRAY_STAINED_GLASS_PANE");
        Material fillMat = Material.matchMaterial(fillMatStr);
        if (fillMat == null) fillMat = Material.GRAY_STAINED_GLASS_PANE;

        ItemStack pane = new ItemStack(fillMat);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui." + guiKey + ".filler.name", " ")));
            pane.setItemMeta(paneMeta);
        }
        for (int i = 0; i < size; i++) {
            gui.setItem(i, pane);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && 
            event.getFrom().getBlockZ() == event.getTo().getBlockZ() &&
            event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        Player player = event.getPlayer();
        PlotData plotAtTo = getPlotAt(event.getTo());
        PlotData currentPlot = playerCurrentPlot.get(player.getUniqueId());

        if (currentPlot != plotAtTo) {
            if (currentPlot != null) {
                String leaveMsg = messagesConfig.getString("actionbar-exit", "&7Wyszedłeś z działki gracza &e{owner}!");
                leaveMsg = leaveMsg.replace("{owner}", currentPlot.ownerName);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(ChatColor.translateAlternateColorCodes('&', leaveMsg)));
            }
            if (plotAtTo != null) {
                String enterMsg = messagesConfig.getString("actionbar-enter", "&7Wszedłeś na działkę gracza &e{owner}!");
                enterMsg = enterMsg.replace("{owner}", plotAtTo.ownerName);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(ChatColor.translateAlternateColorCodes('&', enterMsg)));
            }
            if (plotAtTo == null) {
                playerCurrentPlot.remove(player.getUniqueId());
            } else {
                playerCurrentPlot.put(player.getUniqueId(), plotAtTo);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        ItemStack target = getDzialkaBlock();
        if (item.getType() == target.getType() && item.hasItemMeta() && 
            item.getItemMeta().getDisplayName().equals(target.getItemMeta().getDisplayName())) {
            
            Location loc = event.getBlock().getLocation();
            if (!canBuildWG(player, loc)) {
                player.sendMessage(getMsg("wg-claim-forbidden"));
                event.setCancelled(true);
                return;
            }

            UUID uuid = player.getUniqueId();
            if (plotsByOwner.containsKey(uuid)) {
                player.sendMessage(getMsg("already-has-plot"));
                event.setCancelled(true);
                return;
            }

            if (checkOverlap(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ(), 0, uuid)) {
                player.sendMessage(getMsg("plot-overlap"));
                event.setCancelled(true);
                return;
            }

            String dateStr = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date());
            PlotData plot = new PlotData(UUID.randomUUID(), uuid, player.getName(), loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), 0, 0, dateStr);
            plotsByOwner.put(uuid, plot);
            allPlots.add(plot);

            try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO plots VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 1, 0)")) {
                ps.setString(1, plot.id.toString());
                ps.setString(2, plot.owner.toString());
                ps.setString(3, plot.ownerName);
                ps.setString(4, plot.world);
                ps.setInt(5, plot.centerX);
                ps.setInt(6, plot.centerY);
                ps.setInt(7, plot.centerZ);
                ps.setInt(8, plot.level);
                ps.setInt(9, plot.maxMembersLevel);
                ps.setString(10, plot.createdAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }

            player.sendMessage(getMsg("dzialka-created"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            
            createHologram(plot);
            spawnEffects(plot);
        }
    }

    private void spawnEffects(PlotData plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) return;
        int radius = plot.getSize() / 2;
        int minX = plot.centerX - radius;
        int maxX = plot.centerX + radius - 1;
        int minZ = plot.centerZ - radius;
        int maxZ = plot.centerZ + radius - 1;
        double y = plot.centerY + 0.1;

        List<Location> borderLocs = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            borderLocs.add(new Location(world, x + 0.5, y, minZ + 0.5));
            borderLocs.add(new Location(world, x + 0.5, y, maxZ + 0.5));
        }
        for (int z = minZ; z <= maxZ; z++) {
            borderLocs.add(new Location(world, minX + 0.5, y, z + 0.5));
            borderLocs.add(new Location(world, maxX + 0.5, y, z + 0.5));
        }

        for (int i = 0; i < 5; i++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                for (Location l : borderLocs) {
                    world.spawnParticle(Particle.FLAME, l, 1, 0, 0, 0, 0);
                }
            }, i * 20L);
        }
    }

    private void showBorderForPlayer(Player player, PlotData plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) return;
        int radius = plot.getSize() / 2;
        int minX = plot.centerX - radius;
        int maxX = plot.centerX + radius - 1;
        int minZ = plot.centerZ - radius;
        int maxZ = plot.centerZ + radius - 1;
        
        for (int step = 0; step < 3; step++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) return;
                double playerY = player.getLocation().getY();
                for (int x = minX; x <= maxX; x++) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x + 0.5, playerY + 0.5, minZ + 0.5, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, x + 0.5, playerY + 0.5, maxZ + 0.5, 1, 0, 0, 0, 0);
                }
                for (int z = minZ; z <= maxZ; z++) {
                    player.spawnParticle(Particle.HAPPY_VILLAGER, minX + 0.5, playerY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, maxX + 0.5, playerY + 0.5, z + 0.5, 1, 0, 0, 0, 0);
                }
            }, step * 20L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();
        PlotData plot = getPlotAt(loc);

        if (plot != null) {
            Player player = event.getPlayer();
            ItemStack target = getDzialkaBlock();
            if (block.getType() == target.getType() && block.getX() == plot.centerX && block.getY() == plot.centerY && block.getZ() == plot.centerZ) {
                event.setCancelled(true);
                if (player.getUniqueId().equals(plot.owner) || player.hasPermission("getdzialki.admin")) {
                    openConfirmDeleteGUI(player, plot);
                } else {
                    player.sendMessage(getMsg("not-owner"));
                }
                return;
            }

            if (!hasAccess(player, plot)) {
                player.sendMessage(getMsg("no-permission"));
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        PlotData pistonPlot = getPlotAt(piston.getLocation());

        for (Block b : event.getBlocks()) {
            PlotData plot = getPlotAt(b.getLocation());
            if (plot != null && b.getX() == plot.centerX && b.getY() == plot.centerY && b.getZ() == plot.centerZ) {
                event.setCancelled(true);
                return;
            }
        }

        for (Block b : event.getBlocks()) {
            Location oldLoc = b.getLocation();
            Location newLoc = b.getRelative(direction).getLocation();
            
            PlotData oldPlot = getPlotAt(oldLoc);
            PlotData newPlot = getPlotAt(newLoc);

            if (newPlot != null) {
                if (pistonPlot == null || !pistonPlot.owner.equals(newPlot.owner)) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (oldPlot != null && (pistonPlot == null || !pistonPlot.owner.equals(oldPlot.owner))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        PlotData pistonPlot = getPlotAt(piston.getLocation());

        for (Block b : event.getBlocks()) {
            PlotData plot = getPlotAt(b.getLocation());
            if (plot != null && b.getX() == plot.centerX && b.getY() == plot.centerY && b.getZ() == plot.centerZ) {
                event.setCancelled(true);
                return;
            }
        }

        for (Block b : event.getBlocks()) {
            Location oldLoc = b.getLocation();
            Location newLoc = b.getRelative(direction.getOppositeFace()).getLocation();

            PlotData oldPlot = getPlotAt(oldLoc);
            PlotData newPlot = getPlotAt(newLoc);

            if (oldPlot != null) {
                if (pistonPlot == null || !pistonPlot.owner.equals(oldPlot.owner)) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (newPlot != null && (pistonPlot == null || !pistonPlot.owner.equals(newPlot.owner))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Block fromBlock = event.getBlock();
        Block toBlock = event.getToBlock();
        PlotData plotTo = getPlotAt(toBlock.getLocation());
        PlotData plotFrom = getPlotAt(fromBlock.getLocation());
        
        if (plotTo != null) {
            if (plotFrom != plotTo) {
                event.setCancelled(true);
                return;
            }
            if (!plotTo.liquids) {
                event.setCancelled(true);
            }
        }
    }

    private boolean hasAccess(Player player, PlotData plot) {
        if (plot == null) return true;
        if (player.getUniqueId().equals(plot.owner) || player.hasPermission("getdzialki.admin")) return true;
        Set<UUID> members = plotMembers.get(plot.owner);
        return members != null && members.contains(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block b = event.getClickedBlock();
        PlotData plot = getPlotAt(b.getLocation());
        
        if (plot != null) {
            Player player = event.getPlayer();
            ItemStack target = getDzialkaBlock();
            if (b.getType() == target.getType() && b.getX() == plot.centerX && b.getY() == plot.centerY && b.getZ() == plot.centerZ) {
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    event.setCancelled(true);
                    if (player.getUniqueId().equals(plot.owner) || player.hasPermission("getdzialki.admin")) {
                        openSettingsGUI(player, plot);
                    } else {
                        player.sendMessage(getMsg("not-owner"));
                    }
                    return;
                }
            }
            
            if (!hasAccess(player, plot)) {
                player.sendMessage(getMsg("no-permission"));
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        PlotData plot = getPlotAt(event.getEntity().getLocation());
        if (plot != null) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL && !plot.fall) {
                event.setCancelled(true);
            } else if ((event.getCause() == EntityDamageEvent.DamageCause.FIRE || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK || event.getCause() == EntityDamageEvent.DamageCause.LAVA) && !plot.fire) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        PlotData plot = getPlotAt(event.getEntity().getLocation());
        if (plot != null) {
            if (event.getEntity() instanceof Animals && event.getDamager() instanceof Player) {
                if (!hasAccess((Player) event.getDamager(), plot)) {
                    ((Player) event.getDamager()).sendMessage(getMsg("no-permission"));
                    event.setCancelled(true);
                }
            } else if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
                if (!plot.pvp) event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        List<Block> toRemove = new ArrayList<>();
        for (Block b : event.blockList()) {
            PlotData plot = getPlotAt(b.getLocation());
            if (plot != null) {
                ItemStack target = getDzialkaBlock();
                if (b.getType() == target.getType() && b.getX() == plot.centerX && b.getY() == plot.centerY && b.getZ() == plot.centerZ) {
                    toRemove.add(b);
                    continue;
                }
                if (!plot.explode) {
                    toRemove.add(b);
                }
            }
        }
        event.blockList().removeAll(toRemove);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("getdzialka")) {
            if (!sender.hasPermission("getdzialki.admin")) {
                sender.sendMessage(ChatColor.RED + "Nie masz uprawnien do tej komendy.");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                createMessagesConfig();
                registerRecipe(); 
                refreshAllHolograms();
                sender.sendMessage(ChatColor.GREEN + "[getDzialki] Konfiguracja, wiadomosci oraz hologramy zostaly pomyslnie przeladowane!");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("killholograms")) {
                // Usuń wszystkie TextDisplay z naszym tagiem PDC we wszystkich światach
                int count = 0;
                for (World w : Bukkit.getWorlds()) {
                    for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                        if (td.getPersistentDataContainer().has(hologramTagKey, PersistentDataType.BYTE)) {
                            td.remove();
                            count++;
                        }
                    }
                }
                plotHolograms.clear();
                sender.sendMessage(ChatColor.YELLOW + "[getDzialki] Usunieto " + count + " hologramow. Uzyj /getdzialka reload aby je odtworzyc.");
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("teleportuj")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Tylko gracz moze użyć tej komendy.");
                    return true;
                }
                Player player = (Player) sender;
                String targetName = args[1];
                PlotData foundPlot = null;
                for (PlotData plot : allPlots) {
                    if (plot.ownerName.equalsIgnoreCase(targetName)) {
                        foundPlot = plot;
                        break; 
                    }
                }
                if (foundPlot == null) {
                    player.sendMessage(ChatColor.RED + "Nie znaleziono działki należącej do gracza: " + targetName);
                    return true;
                }
                World world = Bukkit.getWorld(foundPlot.world);
                if (world != null) {
                    int highestY = world.getHighestBlockYAt(foundPlot.centerX, foundPlot.centerZ);
                    player.teleport(new Location(world, foundPlot.centerX + 0.5, highestY + 1.0, foundPlot.centerZ + 0.5));
                    player.sendMessage(ChatColor.GREEN + "Zostałeś przeteleportowany na działkę gracza " + targetName);
                } else {
                    player.sendMessage(ChatColor.RED + "Świat działki jest niedostępny.");
                }
                return true;
            }
            sender.sendMessage(ChatColor.RED + "Uzyj: /getdzialka reload LUB /getdzialka teleportuj {nick} {numer}");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Tylko gracz moze uzywac tej komendy.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 1 && args[0].equalsIgnoreCase("pomoc")) {
            player.sendMessage(getRawMsg("help-line-1"));
            player.sendMessage(getRawMsg("help-line-2"));
            player.sendMessage(getRawMsg("help-line-3"));
            player.sendMessage(getRawMsg("help-line-4"));
            player.sendMessage(getRawMsg("help-line-5"));
            player.sendMessage(getRawMsg("help-line-6"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("crafting")) {
            openCraftingGUI(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("ulepsz")) {
            UUID uuid = player.getUniqueId();
            if (!plotsByOwner.containsKey(uuid)) {
                player.sendMessage(getMsg("no-plot"));
                return true;
            }
            openUpgradeGUI(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("home")) {
            UUID uuid = player.getUniqueId();
            if (!plotsByOwner.containsKey(uuid)) {
                player.sendMessage(getMsg("no-plot"));
                return true;
            }
            PlotData plot = plotsByOwner.get(uuid);
            World world = Bukkit.getWorld(plot.world);
            if (world != null) {
                int highestY = world.getHighestBlockYAt(plot.centerX, plot.centerZ);
                player.teleport(new Location(world, plot.centerX + 0.5, highestY + 1.0, plot.centerZ + 0.5));
                player.sendMessage(getMsg("home-teleport"));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("ustawienia")) {
            PlotData plot = getPlotAt(player.getLocation());
            if (plot == null || (!plot.owner.equals(player.getUniqueId()) && !player.hasPermission("getdzialki.admin"))) {
                player.sendMessage(getMsg("not-owner"));
                return true;
            }
            openSettingsGUI(player, plot);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("dostep")) {
            String action = args[1];
            String targetName = args[2];
            UUID ownerUUID = player.getUniqueId();
            if (!plotsByOwner.containsKey(ownerUUID)) {
                player.sendMessage(getMsg("no-plot"));
                return true;
            }
            PlotData plot = plotsByOwner.get(ownerUUID);

            if (action.equalsIgnoreCase("dodaj")) {
                Set<UUID> currentMembers = plotMembers.computeIfAbsent(ownerUUID, k -> new HashSet<>());
                if (currentMembers.size() >= plot.getMaxMembers()) {
                    player.sendMessage(getMsg("max-members-reached"));
                    return true;
                }
                Player target = Bukkit.getPlayer(targetName);
                UUID targetUUID = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
                if (targetUUID == null) {
                    player.sendMessage(getMsg("player-not-found"));
                    return true;
                }
                currentMembers.add(targetUUID);
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO members VALUES (?, ?)")) {
                    ps.setString(1, ownerUUID.toString());
                    ps.setString(2, targetUUID.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                player.sendMessage(getMsg("member-added").replace("%player%", targetName));
                return true;
            } else if (action.equalsIgnoreCase("usun")) {
                Player target = Bukkit.getPlayer(targetName);
                UUID targetUUID = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
                if (plotMembers.containsKey(ownerUUID)) {
                    plotMembers.get(ownerUUID).remove(targetUUID);
                }
                try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM members WHERE owner=? AND member=?")) {
                    ps.setString(1, ownerUUID.toString());
                    ps.setString(2, targetUUID.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                player.sendMessage(getMsg("member-removed").replace("%player%", targetName));
                return true;
            }
        }
        player.sendMessage(getMsg("usage"));
        return true;
    }

    private void openCraftingGUI(Player player) {
        String guiTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.crafting", "&8Crafting Bloku Dzialki"));
        Inventory gui = Bukkit.createInventory(null, 45, guiTitle);
        setFillerItems(gui, "crafting", 45);
        
        gui.setItem(11, new ItemStack(Material.OAK_PLANKS));
        gui.setItem(12, new ItemStack(Material.OAK_PLANKS));
        gui.setItem(13, new ItemStack(Material.OAK_PLANKS));
        gui.setItem(20, new ItemStack(Material.OAK_PLANKS));
        
        String materialName = getConfig().getString("block-type", "NOTE_BLOCK");
        Material centerMat = Material.matchMaterial(materialName);
        if (centerMat == null) centerMat = Material.NOTE_BLOCK;
        gui.setItem(21, new ItemStack(centerMat));
        
        gui.setItem(22, new ItemStack(Material.OAK_PLANKS));
        gui.setItem(29, new ItemStack(Material.IRON_INGOT));
        gui.setItem(30, new ItemStack(Material.IRON_INGOT));
        gui.setItem(31, new ItemStack(Material.IRON_INGOT));

        gui.setItem(24, getDzialkaBlock());
        player.openInventory(gui);
    }

    private void openSettingsGUI(Player player, PlotData plot) {
        String guiTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.settings", "&8ᴜꜱᴛᴀᴡɪᴇɴɪᴀ ᴅᴢɪᴀʟᴋɪ"));
        int size = getConfig().getInt("gui.settings-layout.size", 27);
        Inventory gui = Bukkit.createInventory(null, size, guiTitle);
        setFillerItems(gui, "settings-layout", size);

        gui.setItem(getConfig().getInt("gui.settings-layout.slots.fall", 10), createConfiguredSettingItem("fall", plot.fall));
        gui.setItem(getConfig().getInt("gui.settings-layout.slots.fire", 11), createConfiguredSettingItem("fire", plot.fire));
        gui.setItem(getConfig().getInt("gui.settings-layout.slots.explode", 12), createConfiguredSettingItem("explode", plot.explode));
        gui.setItem(getConfig().getInt("gui.settings-layout.slots.pvp", 13), createConfiguredSettingItem("pvp", plot.pvp));
        gui.setItem(getConfig().getInt("gui.settings-layout.slots.liquids", 14), createConfiguredSettingItem("liquids", plot.liquids));
        gui.setItem(getConfig().getInt("gui.settings-layout.slots.border", 16), createConfiguredSettingItem("border", false));
        
        player.openInventory(gui);
    }

    private void openConfirmDeleteGUI(Player player, PlotData plot) {
        pendingDelete.put(player.getUniqueId(), plot);
        String guiTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.confirm", "&cPotwierdź usunięcie"));
        int size = getConfig().getInt("gui.confirm-layout.size", 27);
        Inventory gui = Bukkit.createInventory(null, size, guiTitle);
        setFillerItems(gui, "confirm-layout", size);

        String acceptMatStr = getConfig().getString("gui.confirm.accept.item", "LIME_WOOL");
        Material acceptMat = Material.matchMaterial(acceptMatStr);
        if (acceptMat == null) acceptMat = Material.LIME_WOOL;

        ItemStack acceptItem = new ItemStack(acceptMat);
        ItemMeta acceptMeta = acceptItem.getItemMeta();
        if (acceptMeta != null) {
            acceptMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.confirm.accept.name", "&aPotwierdzam usuniecie")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("gui.confirm.accept.lore")) lore.add(ChatColor.translateAlternateColorCodes('&', line));
            acceptMeta.setLore(lore);
            acceptItem.setItemMeta(acceptMeta);
        }

        String cancelMatStr = getConfig().getString("gui.confirm.cancel.item", "RED_WOOL");
        Material cancelMat = Material.matchMaterial(cancelMatStr);
        if (cancelMat == null) cancelMat = Material.RED_WOOL;

        ItemStack cancelItem = new ItemStack(cancelMat);
        ItemMeta cancelMeta = cancelItem.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.confirm.cancel.name", "&cAnuluj")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("gui.confirm.cancel.lore")) lore.add(ChatColor.translateAlternateColorCodes('&', line));
            cancelMeta.setLore(lore);
            cancelItem.setItemMeta(cancelMeta);
        }

        gui.setItem(getConfig().getInt("gui.confirm-layout.slots.accept", 11), acceptItem);
        gui.setItem(getConfig().getInt("gui.confirm-layout.slots.cancel", 15), cancelItem);
        player.openInventory(gui);
    }

    private ItemStack createConfiguredSettingItem(String key, boolean state) {
        String path = "gui.settings." + key + ".";
        String matStr = getConfig().getString(path + "item", "STONE");
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString(path + "name", "Item")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList(path + "lore")) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("{status}", state ? "§aWłączone" : "§cWyłączone")));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openUpgradeGUI(Player player) {
        PlotData plot = plotsByOwner.get(player.getUniqueId());
        if (plot == null) return;

        String guiTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.upgrade", "&8ᴜʟᴇᴘꜱᴢ ᴅᴢɪᴀʟᴋᴇ"));
        int size = getConfig().getInt("gui.upgrade-layout.size", 27);
        Inventory gui = Bukkit.createInventory(null, size, guiTitle);
        setFillerItems(gui, "upgrade-layout", size);

        int maxSizeLevel = getConfig().getInt("upgrades.max-size-level", 5);
        int maxMemberLevel = getConfig().getInt("upgrades.max-member-level", 4);

        // --- ULEPSZENIE WIELKOŚCI ---
        String sizeMatStr = getConfig().getString("gui.size-upgrade.item", "BEACON");
        Material sizeMat = Material.matchMaterial(sizeMatStr);
        if (sizeMat == null) sizeMat = Material.BEACON;
        ItemStack sizeItem = new ItemStack(sizeMat);
        ItemMeta sizeMeta = sizeItem.getItemMeta();
        if (sizeMeta != null) {
            sizeMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.size-upgrade.name", "&aUlepsz wielkość")));
            List<String> lore = new ArrayList<>();
            if (plot.level >= maxSizeLevel) {
                for (String line : getConfig().getStringList("gui.size-upgrade.max-lore")) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("{current_size}", String.valueOf(plot.getSize()))));
                }
            } else {
                double cost = (plot.level + 1) * getConfig().getDouble("economy.size-upgrade-multiplier", 2500.0);
                for (String line : getConfig().getStringList("gui.size-upgrade.lore")) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line
                            .replace("{current_size}", String.valueOf(plot.getSize()))
                            .replace("{next_size}", String.valueOf(plot.getSize() + 4))
                            .replace("{cost}", String.valueOf(cost))));
                }
            }
            sizeMeta.setLore(lore);
            sizeItem.setItemMeta(sizeMeta);
        }
        gui.setItem(getConfig().getInt("gui.upgrade-layout.slots.size-upgrade", 11), sizeItem);

        // --- ULEPSZENIE LIMITU CZŁONKÓW ---
        String memMatStr = getConfig().getString("gui.member-upgrade.item", "PLAYER_HEAD");
        Material memMat = Material.matchMaterial(memMatStr);
        if (memMat == null) memMat = Material.PLAYER_HEAD;
        ItemStack memberItem = new ItemStack(memMat);
        ItemMeta memberMeta = memberItem.getItemMeta();
        if (memberMeta != null) {
            memberMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.member-upgrade.name", "&bUlepsz limit członków")));
            List<String> lore = new ArrayList<>();
            if (plot.maxMembersLevel >= maxMemberLevel) {
                for (String line : getConfig().getStringList("gui.member-upgrade.max-lore")) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("{current_limit}", String.valueOf(plot.getMaxMembers()))));
                }
            } else {
                double cost = (plot.maxMembersLevel + 1) * getConfig().getDouble("economy.member-upgrade-multiplier", 5000.0);
                for (String line : getConfig().getStringList("gui.member-upgrade.lore")) {
                    lore.add(ChatColor.translateAlternateColorCodes('&', line
                            .replace("{current_limit}", String.valueOf(plot.getMaxMembers()))
                            .replace("{next_limit}", String.valueOf(plot.getMaxMembers() + 2))
                            .replace("{cost}", String.valueOf(cost))));
                }
            }
            memberMeta.setLore(lore);
            memberItem.setItemMeta(memberMeta);
        }
        gui.setItem(getConfig().getInt("gui.upgrade-layout.slots.member-upgrade", 15), memberItem);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String cTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.crafting", "&8Crafting Bloku Dzialki"));
        String sTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.settings", "&8ᴜꜱᴛᴀᴡɪᴇɴɪᴀ ᴅᴢɪᴀʟᴋɪ"));
        String uTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.upgrade", "&8ᴜʟᴇᴘꜱᴢ ᴅᴢɪᴀzlke"));
        String cfTitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("gui.titles.confirm", "&cPotwierdź usunięcie"));
        
        if (title.equals(cTitle)) {
            event.setCancelled(true);
            return;
        }
        
        if (title.equals(sTitle)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            Player player = (Player) event.getWhoClicked();
            PlotData plot = getPlotAt(player.getLocation());
            if (plot == null) return;
            
            int slot = event.getRawSlot();
            boolean clickedValid = false;

            if (slot == getConfig().getInt("gui.settings-layout.slots.fall", 10)) { plot.fall = !plot.fall; clickedValid = true; }
            else if (slot == getConfig().getInt("gui.settings-layout.slots.fire", 11)) { plot.fire = !plot.fire; clickedValid = true; }
            else if (slot == getConfig().getInt("gui.settings-layout.slots.explode", 12)) { plot.explode = !plot.explode; clickedValid = true; }
            else if (slot == getConfig().getInt("gui.settings-layout.slots.pvp", 13)) { plot.pvp = !plot.pvp; clickedValid = true; }
            else if (slot == getConfig().getInt("gui.settings-layout.slots.liquids", 14)) { plot.liquids = !plot.liquids; clickedValid = true; }
            else if (slot == getConfig().getInt("gui.settings-layout.slots.border", 16)) {
                player.closeInventory();
                showBorderForPlayer(player, plot);
                player.sendMessage(getMsg("border-shown"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                return;
            }
            
            if (clickedValid) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
                updateSettingsInDB(plot);
                openSettingsGUI(player, plot);
            }
            return;
        }

        if (title.equals(uTitle)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            Player player = (Player) event.getWhoClicked();
            PlotData plot = plotsByOwner.get(player.getUniqueId());
            if (plot == null) return;

            int maxSizeLevel = getConfig().getInt("upgrades.max-size-level", 5);
            int maxMemberLevel = getConfig().getInt("upgrades.max-member-level", 4);
            int slot = event.getRawSlot();

            if (slot == getConfig().getInt("gui.upgrade-layout.slots.size-upgrade", 11)) { 
                if (plot.level >= maxSizeLevel) {
                    player.sendMessage(ChatColor.RED + "Osiągnąłeś już maksymalny poziom wielkości działki!");
                    return;
                }
                int nextLevel = plot.level + 1;
                double cost = nextLevel * getConfig().getDouble("economy.size-upgrade-multiplier", 2500.0);

                if (econ == null || !econ.has(player, cost)) {
                    player.sendMessage(getMsg("no-money").replace("%cost%", String.valueOf(cost)));
                    return;
                }
                if (checkOverlap(plot.world, plot.centerX, plot.centerZ, nextLevel, player.getUniqueId())) {
                    player.sendMessage(getMsg("plot-overlap"));
                    return;
                }

                econ.withdrawPlayer(player, cost);
                plot.level = nextLevel;

                try (PreparedStatement ps = dbConnection.prepareStatement("UPDATE plots SET level=? WHERE id=?")) {
                    ps.setInt(1, plot.level);
                    ps.setString(2, plot.id.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }

                player.sendMessage(getMsg("plot-upgraded")
                        .replace("%level%", String.valueOf(plot.level))
                        .replace("%size%", String.valueOf(plot.getSize()))
                        .replace("%cost%", String.valueOf(cost)));
                
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                removeHologram(plot);
                createHologram(plot);
                spawnEffects(plot);
                openUpgradeGUI(player);
            } 
            else if (slot == getConfig().getInt("gui.upgrade-layout.slots.member-upgrade", 15)) { 
                if (plot.maxMembersLevel >= maxMemberLevel) {
                    player.sendMessage(ChatColor.RED + "Osiągnąłeś już maksymalny limit członków działki!");
                    return;
                }
                int nextMembersLevel = plot.maxMembersLevel + 1;
                double cost = nextMembersLevel * getConfig().getDouble("economy.member-upgrade-multiplier", 5000.0);

                if (econ == null || !econ.has(player, cost)) {
                    player.sendMessage(getMsg("no-money").replace("%cost%", String.valueOf(cost)));
                    return;
                }

                econ.withdrawPlayer(player, cost);
                plot.maxMembersLevel = nextMembersLevel;

                try (PreparedStatement ps = dbConnection.prepareStatement("UPDATE plots SET members_level=? WHERE id=?")) {
                    ps.setInt(1, plot.maxMembersLevel);
                    ps.setString(2, plot.id.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }

                player.sendMessage(getMsg("members-limit-upgraded")
                        .replace("%limit%", String.valueOf(plot.getMaxMembers()))
                        .replace("%cost%", String.valueOf(cost)));

                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                openUpgradeGUI(player);
            }
        }

        if (title.equals(cfTitle)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            Player player = (Player) event.getWhoClicked();
            int slot = event.getRawSlot();
            PlotData plot = pendingDelete.get(player.getUniqueId());
            if (plot == null) {
                player.closeInventory();
                return;
            }

            if (slot == getConfig().getInt("gui.confirm-layout.slots.accept", 11)) {
                pendingDelete.remove(player.getUniqueId());
                removeHologram(plot);
                plotsByOwner.remove(plot.owner);
                allPlots.remove(plot);

                try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM plots WHERE id=?")) {
                    ps.setString(1, plot.id.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM members WHERE owner=?")) {
                    ps.setString(1, plot.owner.toString());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                plotMembers.remove(plot.owner);

                Location bLoc = new Location(Bukkit.getWorld(plot.world), plot.centerX, plot.centerY, plot.centerZ);
                bLoc.getBlock().setType(Material.AIR);
                player.closeInventory();
                bLoc.getWorld().dropItemNaturally(bLoc, getDzialkaBlock());
                player.sendMessage(getMsg("dzialka-removed"));
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
            } else if (slot == getConfig().getInt("gui.confirm-layout.slots.cancel", 15)) {
                pendingDelete.remove(player.getUniqueId());
                player.closeInventory();
            }
        }
    }

    private void updateSettingsInDB(PlotData plot) {
        try (PreparedStatement ps = dbConnection.prepareStatement("UPDATE plots SET fall=?, fire=?, explode=?, pvp=?, liquids=? WHERE id=?")) {
            ps.setInt(1, plot.fall ? 1 : 0);
            ps.setInt(2, plot.fire ? 1 : 0);
            ps.setInt(3, plot.explode ? 1 : 0);
            ps.setInt(4, plot.pvp ? 1 : 0);
            ps.setInt(5, plot.liquids ? 1 : 0);
            ps.setString(6, plot.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("getdzialka")) {
            if (sender.hasPermission("getdzialki.admin")) {
                if (args.length == 1) return Arrays.asList("reload", "teleportuj");
                if (args.length == 2 && args[0].equalsIgnoreCase("teleportuj")) {
                    List<String> plots = new ArrayList<>();
                    for (PlotData plot : allPlots) plots.add(plot.ownerName);
                    return plots;
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("teleportuj")) return Collections.singletonList("1");
            }
            return Collections.emptyList();
        }

        if (args.length == 1) return Arrays.asList("dostep", "crafting", "ulepsz", "home", "ustawienia", "pomoc");
        if (args.length == 2 && args[0].equalsIgnoreCase("dostep")) return Arrays.asList("dodaj", "usun");
        if (args.length == 3 && args[0].equalsIgnoreCase("dostep")) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            return players;
        }
        return Collections.emptyList();
    }
}