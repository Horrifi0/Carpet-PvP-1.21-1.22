package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.CloneCommands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CloneCommands.class)
public abstract class CloneCommands_fillUpdatesMixin
{
    // 1.21.8: target invocation point changed in CloneCommands; make this redirect optional to avoid hard crash if not present
    @Redirect(method = "clone", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"
    ), require = 0)
    private static void conditionalUpdating(ServerLevel serverWorld, BlockPos blockPos_1, Block block_1)
    {
        if (CarpetSettings.fillUpdates) serverWorld.updateNeighborsAt(blockPos_1, block_1);
    }
}
