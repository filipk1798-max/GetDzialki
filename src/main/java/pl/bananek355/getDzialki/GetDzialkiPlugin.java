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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
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
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

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
        if (getCommand("dzialka") != null) {
            getCommand("dzialka").setExecutor(this);
            getCommand("dzialka").setTabCompleter(this);
        }
        if (getCommand("getdzialka") != null) {
            getCommand("getdzialka").setExecutor(this);
            getCommand("getdzialka").setTabCompleter(this);
        }
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
        if (hologramValidatorTask != -1) {
            Bukkit.getScheduler().cancelTask(hologramValidatorTask);
            hologramValidatorTask = -1;
        }
        for (List<TextDisplay> displays : plotHolograms.values()) {
            for (TextDisplay td : displays) {
                if (td != null && !td.isDead()) td.remove();
            }
        }
        plotHolograms.clear();

        for (PlotData plot : allPlots) {
            World plotWorld = Bukkit.getWorld(plot.world);
            if (plotWorld == null) continue;
            int chunkX = plot.centerX >> 4;
            int chunkZ = plot.centerZ >> 4;
            if (!plotWorld.isChunkLoaded(chunkX, chunkZ)) {
                plotWorld.loadChunk(chunkX, chunkZ, false);
            }
        }

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

    private int getPlayersCount(PlotData plot) {
        Set<UUID> members = plotMembers.get(plot.owner);
        return members != null ? members.size() : 0;
    }

    private void refreshHologram(PlotData plot) {
        removeHologram(plot);
        createHologram(plot);
    }

    private void createHologram(PlotData plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) return;

        Location blockLoc = new Location(world, plot.centerX, plot.centerY, plot.centerZ);
        String dzialkaBlockMat = getConfig().getString("block-type", "NOTE_BLOCK");
        Material dzialkaBlockType = Material.matchMaterial(dzialkaBlockMat);
        if (dzialkaBlockType == null) dzialkaBlockType = Material.NOTE_BLOCK;
        if (blockLoc.getBlock().getType() != dzialkaBlockType) return;

        int chunkX = plot.centerX >> 4;
        int chunkZ = plot.centerZ >> 4;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) chunk.load(false);
        NamespacedKey ownerKeyDedup = new NamespacedKey(this, "getdzialki_owner");
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof TextDisplay)) continue;
            TextDisplay existingTd = (TextDisplay) e;
            PersistentDataContainer existingPdc = existingTd.getPersistentDataContainer();
            if (!existingPdc.has(hologramTagKey, PersistentDataType.BYTE)) continue;
            String existingOwnerStr = existingPdc.get(ownerKeyDedup, PersistentDataType.STRING);
            if (existingOwnerStr != null && existingOwnerStr.equals(plot.owner.toString())) {
                existingTd.remove();
            }
        }

        List<String> lines = getConfig().getStringList("hologram-lines");
        int totalLines = lines.size();
        if (totalLines == 0) return;

        double lineSpacing = 0.3;
        double baseY = plot.centerY + 2.0 + (totalLines - 1) * lineSpacing;
        Location baseLoc = new Location(world, plot.centerX + 0.5, baseY, plot.centerZ + 0.5);

        String playersValue = getPlayersCount(plot) + "/" + plot.getMaxMembers();

        List<TextDisplay> displays = new ArrayList<>();

        for (int i = 0; i < totalLines; i++) {
            String line = lines.get(i)
                    .replace("{owner}", plot.ownerName)
                    .replace("{size}", String.valueOf(plot.getSize()))
                    .replace("{created_at}", plot.createdAt)
                    .replace("{players}", playersValue);
            final String coloredLine = ChatColor.translateAlternateColorCodes('&', line);

            Location lineLoc = baseLoc.clone().add(0, -i * lineSpacing, 0);

            TextDisplay td = world.spawn(lineLoc, TextDisplay.class, display -> {
                display.setText(coloredLine);
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setShadowed(true);
                display.setSeeThrough(false);
                display.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                display.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(1f, 1f, 1f),
                        new AxisAngle4f(0, 0, 0, 1)
                ));
                display.setInvulnerable(true);
                display.getPersistentDataContainer().set(hologramTagKey, PersistentDataType.BYTE, (byte) 1);
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
                if (td != null && !td.isDead()) td.remove();
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

                    PlotData plot = plotsByOwner.get(ownerUUID);
                    if (plot == null) { td.remove(); continue; }

                    World plotWorld = Bukkit.getWorld(plot.world);
                    if (plotWorld == null) { td.remove(); continue; }
                    Block block = plotWorld.getBlockAt(plot.centerX, plot.centerY, plot.centerZ);
                    if (block.getType() != finalBlockType) {
                        td.remove();
                    }
                }
            }

            for (PlotData plot : allPlots) {
                World plotWorld = Bukkit.getWorld(plot.world);
                if (plotWorld == null) continue;
                int chunkX = plot.centerX >> 4;
                int chunkZ = plot.centerZ >> 4;
                if (!plotWorld.isChunkLoaded(chunkX, chunkZ)) continue;

                Block block = plotWorld.getBlockAt(plot.centerX, plot.centerY, plot.centerZ);
                if (block.getType() != finalBlockType) continue;

                List<TextDisplay> existing = plotHolograms.get(plot.id);
                boolean needsRespawn = (existing == null || existing.isEmpty());
                if (!needsRespawn) {
                    boolean allAlive = existing.stream().allMatch(td -> td != null && !td.isDead());
                    if (!allAlive) needsRespawn = true;
                }
                if (needsRespawn) {
                    if (existing != null) {
                        existing.forEach(td -> {
                            if (td != null && !td.isDead()) td.remove();
                        });
                    }
                    plotHolograms.remove(plot.id);
                    createHologram(plot);
                }
            }
        }, 100L, 100L);
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

        try {
            getServer().addRecipe(recipe);
        } catch (Exception e) {
            getLogger().warning("Nie udało się zarejestrować receptury: " + e.getMessage());
        }
    }

    private boolean isManager(Player p, PlotData plot) {
        if (p.hasPermission("getdzialki.admin")) return true;
        if (plot.owner.equals(p.getUniqueId())) return true;
        Set<UUID> members = plotMembers.get(plot.owner);
        return members != null && members.contains(p.getUniqueId());
    }

    private void savePlotToDb(PlotData plot) {
        try (PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE plots SET level = ?, members_level = ?, fall = ?, fire = ?, explode = ?, pvp = ?, liquids = ? WHERE id = ?")) {
            ps.setInt(1, plot.level);
            ps.setInt(2, plot.maxMembersLevel);
            ps.setInt(3, plot.fall ? 1 : 0);
            ps.setInt(4, plot.fire ? 1 : 0);
            ps.setInt(5, plot.explode ? 1 : 0);
            ps.setInt(6, plot.pvp ? 1 : 0);
            ps.setInt(7, plot.liquids ? 1 : 0);
            ps.setString(8, plot.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("dzialka")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Komenda tylko dla graczy.");
                return true;
            }
            Player player = (Player) sender;
            player.sendMessage(getMsg("messages.help"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("getdzialka")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("getdzialki.admin")) {
                    sender.sendMessage(getMsg("messages.no_permission"));
                    return true;
                }
                reloadConfig();
                createMessagesConfig();
                refreshAllHolograms();
                sender.sendMessage(getMsg("messages.reloaded"));
                return true;
            }
        }
        return false;
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
        if (args.length == 2 && (args[0].equalsIgnoreCase("ustawienia") || args[0].equalsIgnoreCase("ulepsz")) && sender instanceof Player) {
            Player p = (Player) sender;
            List<String> managedPlots = new ArrayList<>();
            for (PlotData plot : allPlots) {
                if (isManager(p, plot)) managedPlots.add(plot.ownerName);
            }
            return managedPlots;
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onPlayerQuitCleanup(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerCurrentPlot.remove(uuid);
        pendingDelete.remove(uuid);
    }
}
