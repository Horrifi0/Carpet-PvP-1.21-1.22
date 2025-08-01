package carpet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import carpet.commands.CounterCommand;
import carpet.commands.DistanceCommand;
import carpet.commands.DrawCommand;
import carpet.commands.InfoCommand;
import carpet.commands.LogCommand;
import carpet.commands.MobAICommand;
import carpet.commands.PerimeterInfoCommand;
import carpet.commands.PlayerCommand;
import carpet.commands.ProfileCommand;
import carpet.script.ScriptCommand;
import carpet.commands.SpawnCommand;
import carpet.commands.TestCommand;
import carpet.network.ServerNetworkHandler;
import carpet.helpers.HopperCounter;
import carpet.logging.LoggerRegistry;
import carpet.script.CarpetScriptServer;
import carpet.settings.SettingsManager;
import carpet.logging.HUDController;
import carpet.script.external.Carpet;
import carpet.script.external.Vanilla;
import carpet.script.utils.ParticleParser;
import carpet.utils.MobAI;
import carpet.utils.SpawnReporter;
import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.PerfCommand;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

public class CarpetServer
{
    public static MinecraftServer minecraft_server;
    public static CarpetScriptServer scriptServer;
    @SuppressWarnings("removal")
    public static SettingsManager settingsManager;
    public static final List<CarpetExtension> extensions = new ArrayList<>();

    public static void manageExtension(CarpetExtension extension)
    {
        extensions.add(extension);
        if (StackWalker.getInstance().walk(stream -> stream.skip(1)
                .anyMatch(el -> el.getClassName() == CarpetServer.class.getName())))
        {
            CarpetSettings.LOG.warn("""
                    Extension '%s' is registering itself using a mixin into Carpet instead of a regular ModInitializer!
                    This is stupid and will crash the game in future versions!""".formatted(extension.getClass().getSimpleName()));
        }
    }
    public static void onGameStarted()
    {
        if (FabricLoader.getInstance().isModLoaded("worldthreader"))
        {
            CarpetSettings.LOG.error("Disabling Carpet PVP: incompatible mod detected (worldthreader)");
            return;
        }
        @SuppressWarnings("removal")
        SettingsManager mgr = new SettingsManager(CarpetSettings.carpetVersion, "carpet", "Carpet Mod");
        settingsManager = mgr;
        settingsManager.parseSettingsClass(CarpetSettings.class);
        extensions.forEach(CarpetExtension::onGameStarted);
        CarpetScriptServer.parseFunctionClasses();
        CarpetSettings.LOG.info("CARPET PVP LOADED");
    }

    public static void onServerLoaded(MinecraftServer server)
    {
        CarpetServer.minecraft_server = server;
        SpawnReporter.resetSpawnStats(server, true);

        forEachManager(sm -> sm.attachServer(server));
        extensions.forEach(e -> e.onServerLoaded(server));
        scriptServer = new CarpetScriptServer(server);
        Carpet.MinecraftServer_addScriptServer(server, scriptServer);
        MobAI.resetTrackers();
        LoggerRegistry.initLoggers();
    }

    public static void onServerLoadedWorlds(MinecraftServer minecraftServer)
    {
        HopperCounter.resetAll(minecraftServer, true);
        extensions.forEach(e -> e.onServerLoadedWorlds(minecraftServer));
        forEachManager(sm -> sm.initializeScarpetRules());
        scriptServer.initializeForWorld();
    }

    public static void tick(MinecraftServer server)
    {
        HUDController.update_hud(server, null);
        if (scriptServer != null) scriptServer.tick();
        CarpetSettings.impendingFillSkipUpdates.set(false);
        extensions.forEach(e -> e.onTick(server));
    }

    public static void registerCarpetCommands(CommandDispatcher<CommandSourceStack> dispatcher, Commands.CommandSelection environment, CommandBuildContext commandBuildContext)
    {
        if (settingsManager == null)
        {
            return;
        }
        forEachManager(sm -> sm.registerCommand(dispatcher, commandBuildContext));

        ProfileCommand.register(dispatcher, commandBuildContext);
        CounterCommand.register(dispatcher, commandBuildContext);
        LogCommand.register(dispatcher, commandBuildContext);
        SpawnCommand.register(dispatcher, commandBuildContext);
        PlayerCommand.register(dispatcher, commandBuildContext);
        InfoCommand.register(dispatcher, commandBuildContext);
        DistanceCommand.register(dispatcher, commandBuildContext);
        PerimeterInfoCommand.register(dispatcher, commandBuildContext);
        DrawCommand.register(dispatcher, commandBuildContext);
        ScriptCommand.register(dispatcher, commandBuildContext);
        MobAICommand.register(dispatcher, commandBuildContext);
        extensions.forEach(e -> {
            e.registerCommands(dispatcher, commandBuildContext);
        });

        if (environment != Commands.CommandSelection.DEDICATED)
            PerfCommand.register(dispatcher);
        
        if (FabricLoader.getInstance().isDevelopmentEnvironment())
            TestCommand.register(dispatcher);
    }

    public static void onPlayerLoggedIn(ServerPlayer player)
    {
        ServerNetworkHandler.onPlayerJoin(player);
        LoggerRegistry.playerConnected(player);
        extensions.forEach(e -> e.onPlayerLoggedIn(player));
        scriptServer.onPlayerJoin(player);
    }

    public static void onPlayerLoggedOut(ServerPlayer player, Component reason)
    {
        ServerNetworkHandler.onPlayerLoggedOut(player);
        LoggerRegistry.playerDisconnected(player);
        extensions.forEach(e -> e.onPlayerLoggedOut(player));
        CarpetScriptServer runningScriptServer = (player.getServer() == null) ? scriptServer : Vanilla.MinecraftServer_getScriptServer(player.getServer());
        if (runningScriptServer != null && !runningScriptServer.stopAll) {
            runningScriptServer.onPlayerLoggedOut(player, reason);
        }
    }

    public static void clientPreClosing()
    {
        if (scriptServer != null) scriptServer.onClose();
        scriptServer = null;
    }

    public static void onServerClosed(@Nullable MinecraftServer server)
    {
        if (minecraft_server != null)
        {
            if (scriptServer != null) scriptServer.onClose();
            if (server != null && !Vanilla.MinecraftServer_getScriptServer(server).stopAll) {
                Vanilla.MinecraftServer_getScriptServer(server).onClose();
            }

            scriptServer = null;
            ServerNetworkHandler.close();

            LoggerRegistry.stopLoggers();
            HUDController.resetScarpetHUDs();
            ParticleParser.resetCache();
            extensions.forEach(e -> e.onServerClosed(server));
            minecraft_server = null;
        }
    }
    public static void onServerDoneClosing(MinecraftServer server)
    {
        forEachManager(sm -> sm.detachServer());
    }
    public static void forEachManager(java.util.function.Consumer<carpet.api.settings.SettingsManager> consumer)
    {
        consumer.accept(settingsManager);
        for (CarpetExtension e : extensions)
        {
            carpet.api.settings.SettingsManager manager = e.extensionSettingsManager();
            if (manager != null)
            {
                consumer.accept(manager);
            }
        }
    }

    public static void registerExtensionLoggers()
    {
        extensions.forEach(CarpetExtension::registerLoggers);
    }

    public static void onReload(MinecraftServer server)
    {
        scriptServer.reload(server);
        extensions.forEach(e -> e.onReload(server));
    }
    
    private static final Set<CarpetExtension> warnedOutdatedManagerProviders = new HashSet<>();
    static void warnOutdatedManager(CarpetExtension ext)
    {
        if (warnedOutdatedManagerProviders.add(ext))
            CarpetSettings.LOG.warn("""
                    %s is providing a SettingsManager from an outdated method in CarpetExtension!
                    This behaviour will not work in later Carpet versions and the manager won't be registered!""".formatted(ext.getClass().getName()));
    }
}
