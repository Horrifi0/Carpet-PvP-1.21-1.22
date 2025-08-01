package carpet.helpers;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public class ParticleDisplay
{
    public static void drawParticleLine(ServerPlayer player, Vec3 from, Vec3 to, ParticleOptions mainParticle, ParticleOptions accentParticle, int count, double spread)
    {

        if (accentParticle != null) ((net.minecraft.server.level.ServerLevel) player.level()).sendParticles(
                player,
                accentParticle,
                true, true,
                to.x, to.y, to.z, count,
                spread, spread, spread, 0.0);

        double lineLengthSq = from.distanceToSqr(to);
        if (lineLengthSq == 0) return;

        Vec3 incvec = to.subtract(from).normalize();//    multiply(50/sqrt(lineLengthSq));
        for (Vec3 delta = new Vec3(0.0,0.0,0.0);
             delta.lengthSqr() < lineLengthSq;
             delta = delta.add(incvec.scale(player.level().random.nextFloat())))
        {
            ((net.minecraft.server.level.ServerLevel) player.level()).sendParticles(
                    player,
                    mainParticle,
                    true, true,
                    delta.x+from.x, delta.y+from.y, delta.z+from.z, 1,
                    0.0, 0.0, 0.0, 0.0);
        }
    }

}
