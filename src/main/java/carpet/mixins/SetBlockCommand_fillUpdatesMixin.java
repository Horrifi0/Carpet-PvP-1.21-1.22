package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SetBlockCommand.class)
public class SetBlockCommand_fillUpdatesMixin
{
    // 1.21.8: make redirect optional so missing target doesn't crash mixin application
    @Redirect(method = "setBlock", at = @At(
            value = "INVOKE",
            target =  "Lnet/minecraft/server/level/ServerLevel;updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"
    ), require = 0)
    private static void conditionalUpdating(ServerLevel serverWorld, BlockPos blockPos_1, Block block_1)
    {
        if (CarpetSettings.fillUpdates) serverWorld.updateNeighborsAt(blockPos_1, block_1);
    }
}
