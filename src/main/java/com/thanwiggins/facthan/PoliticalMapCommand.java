package com.thanwiggins.facthan;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

// Read-only inspection surface for the Voronoi political map (see PoliticalMapService) - the
// "queryable internal API" this feature's design calls for, so a player (or later, a Xaero
// overlay) has something concrete to query without any political data ever being persisted to
// disk.
@Mod.EventBusSubscriber(modid = FacthanMod.MODID)
public class PoliticalMapCommand {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("political")
                .executes(PoliticalMapCommand::queryAtSelf)
                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                    .executes(PoliticalMapCommand::queryAtPos))
        );
    }

    private static int queryAtSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = player.blockPosition();
        return report(ctx, pos.getX(), pos.getZ());
    }

    private static int queryAtPos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ColumnPos pos = ColumnPosArgument.getColumnPos(ctx, "pos");
        return report(ctx, pos.x(), pos.z());
    }

    private static int report(CommandContext<CommandSourceStack> ctx, int x, int z) {
        long seed = ctx.getSource().getLevel().getSeed();
        Optional<PoliticalMapService.Ownership> ownership = PoliticalMapService.ownerAt(seed, x, z);

        if (ownership.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7No factions configured - the political map is inactive."), false);
            return 0;
        }

        PoliticalMapService.Ownership owned = ownership.get();
        String name = owned.faction() != null ? owned.faction().displayName() : owned.factionId().toString();
        String status = owned.inBufferZone()
                ? "§e(no-man's-land, " + String.format("%.0f", owned.distanceToBorder()) + "m to secure territory)"
                : "§a(secure territory, " + String.format("%.0f", owned.distanceToBorder()) + "m to nearest border)";

        ctx.getSource().sendSuccess(() -> Component.literal("§f(" + x + ", " + z + ") belongs to §b" + name + " §f" + status), false);
        return 1;
    }
}
