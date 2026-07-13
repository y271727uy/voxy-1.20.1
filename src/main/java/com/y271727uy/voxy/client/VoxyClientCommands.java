package com.y271727uy.voxy.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.y271727uy.voxy.common.importer.chunky.ChunkyLiveIntegration;
import com.y271727uy.voxy.common.importer.dh.DhImportAvailability;
import com.y271727uy.voxy.common.importer.dh.DhImportSource;
import com.y271727uy.voxy.common.importing.job.ImportProgressSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Path;

/** Forge client commands matching the useful subset of upstream Voxy's importer controls. */
public final class VoxyClientCommands {
    private VoxyClientCommands() {
    }

    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("voxy")
                .then(Commands.literal("import")
                        .then(Commands.literal("current").executes(VoxyClientCommands::importCurrent))
                        .then(Commands.literal("raw")
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .executes(VoxyClientCommands::importRaw)))
                        .then(Commands.literal("status").executes(VoxyClientCommands::status))
                        .then(Commands.literal("cancel").executes(VoxyClientCommands::cancel))
                        .then(Commands.literal("distant_horizons")
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .executes(VoxyClientCommands::distantHorizons)))
                        .then(Commands.literal("chunky_status").executes(VoxyClientCommands::chunkyStatus))));
    }

    private static int importCurrent(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getSingleplayerServer() == null) {
            return fail(context, "Current-world import requires an integrated singleplayer world");
        }
        Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
        Path region = DimensionType.getStorageFolder(minecraft.level.dimension(), root).resolve("region");
        return start(context, region);
    }

    private static int importRaw(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "path");
        Path path;
        try {
            path = Path.of(value);
            if (!path.isAbsolute()) {
                path = Minecraft.getInstance().gameDirectory.toPath().resolve(path);
            }
        } catch (RuntimeException exception) {
            return fail(context, "Invalid import path: " + exception.getMessage());
        }
        return start(context, path);
    }

    private static int start(CommandContext<CommandSourceStack> context, Path source) {
        try {
            var handle = VoxyClientLifecycle.startRegionImport(source);
            context.getSource().sendSuccess(() -> Component.literal(
                    "Voxy import queued: " + source.toAbsolutePath().normalize()
                            + " (job " + handle.jobId() + ")"), false);
            return 1;
        } catch (RuntimeException exception) {
            return fail(context, "Unable to start Voxy import: " + exception.getMessage());
        }
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ImportProgressSnapshot snapshot = VoxyClientLifecycle.importSnapshot().orElse(null);
        if (snapshot == null) return fail(context, "No Voxy import has run in this world session");
        context.getSource().sendSuccess(() -> Component.literal("Voxy import " + snapshot.state()
                + ": " + snapshot.completed() + "/" + snapshot.total()
                + (snapshot.resumed() ? " (resumed)" : "")), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        if (!VoxyClientLifecycle.cancelImport()) return fail(context, "No cancellable Voxy import is active");
        context.getSource().sendSuccess(() -> Component.literal("Voxy import cancellation requested"), false);
        return 1;
    }

    private static int chunkyStatus(CommandContext<CommandSourceStack> context) {
        var availability = ChunkyLiveIntegration.detect(VoxyClientCommands.class.getClassLoader());
        context.getSource().sendSuccess(() -> Component.literal("Voxy Chunky live integration: "
                + availability.detail() + ". Offline Anvil import is available."), false);
        return 1;
    }

    private static int distantHorizons(CommandContext<CommandSourceStack> context) {
        Path path;
        try {
            path = Path.of(StringArgumentType.getString(context, "path"));
            if (!path.isAbsolute()) path = Minecraft.getInstance().gameDirectory.toPath().resolve(path);
            DhImportSource.inspect(path);
        } catch (Exception exception) {
            return fail(context, "Invalid Distant Horizons source: " + exception.getMessage());
        }
        DhImportAvailability availability = DhImportAvailability.runtime();
        return fail(context, "Distant Horizons import is unavailable: sqlite="
                + availability.sqlite().status() + ", xz=" + availability.xz().status()
                + ", zstd=" + availability.zstd().status());
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
