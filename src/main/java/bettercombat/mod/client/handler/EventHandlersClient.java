package bettercombat.mod.client.handler;

import bettercombat.mod.capability.CapabilityOffhandCooldown;
import bettercombat.mod.client.gui.GuiCrosshairsBC;
import bettercombat.mod.combat.IOffHandAttack;
import bettercombat.mod.combat.ISecondHurtTimer;
import bettercombat.mod.handler.EventHandlers;
import bettercombat.mod.network.PacketHandler;
import bettercombat.mod.network.PacketMainhandAttack;
import bettercombat.mod.network.PacketOffhandAttack;
import bettercombat.mod.util.ConfigurationHandler;
import bettercombat.mod.util.Helpers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

@SideOnly(Side.CLIENT)
public class EventHandlersClient
{
    public static final EventHandlersClient INSTANCE = new EventHandlersClient();

    private final GuiCrosshairsBC gc = new GuiCrosshairsBC();

    private EventHandlersClient() {}

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    public void onMouseEvent(MouseEvent event) {
        KeyBinding attack = Minecraft.getMinecraft().gameSettings.keyBindAttack;
        KeyBinding useItem = Minecraft.getMinecraft().gameSettings.keyBindUseItem;
        if( attack.getKeyCode() < 0 && event.getButton() == attack.getKeyCode() + 100 && event.isButtonstate() ) {
            onMouseLeftClick(event);
        }
        if( useItem.getKeyCode() < 0 && event.getButton() == useItem.getKeyCode() + 100 && event.isButtonstate() ) {
            onMouseRightClick();
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onRenderGameOverlay(RenderGameOverlayEvent.Pre event) {
        switch( event.getType() ) {
            case CROSSHAIRS:
                event.setCanceled(true);
                this.gc.renderAttackIndicator(0.5F, new ScaledResolution(Minecraft.getMinecraft()));
                break;
        }
    }

    public static void onMouseLeftClick(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;

        if( ConfigurationHandler.refoundEnergy ) {
            refoundEnergy(player);
        }

        RayTraceResult mov = getMouseOverExtended(ConfigurationHandler.longerAttack ? 5.0F : 4.0F);
        if( mov != null && mov.entityHit != null ) {
            if( mov.entityHit != player ) {
                if( ConfigurationHandler.requireFullEnergy && player.getCooledAttackStrength(0.5F) < 1.0F ) {
                    event.setCanceled(true);
                    return;
                }

                player.attackTargetEntityWithCurrentItem(mov.entityHit);
                PacketHandler.instance.sendToServer(new PacketMainhandAttack(mov.entityHit.getEntityId()));
            }
        }
    }

    public static void onMouseRightClick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if( player != null ) {
            Item mainItem = null;
            if( !player.getHeldItemMainhand().isEmpty() ) {
                mainItem = player.getHeldItemMainhand().getItem();
            }

            if( ConfigurationHandler.requireFullEnergy
                && Helpers.execNullable(player.getCapability(EventHandlers.TUTO_CAP, null), CapabilityOffhandCooldown::getOffhandCooldown, 1) > 0)
            {
                return;
            }
            ItemStack itemstackOffHand = player.getHeldItem(EnumHand.OFF_HAND);

            boolean canAttack = false;
            if( !itemstackOffHand.isEmpty() ) {
                canAttack = ConfigurationHandler.isItemAttackUsable(itemstackOffHand.getItem());
            }
            if( mainItem != null ) {
                if( !ConfigurationHandler.isItemAttackUsable(mainItem) ) {
                    return;
                }
            }
            if( !canAttack ) {
                return;
            }

            IOffHandAttack oha = player.getCapability(EventHandlers.OFFHAND_CAP, null);
            RayTraceResult mov = getMouseOverExtended(ConfigurationHandler.longerAttack ? 5.0F : 4.0F);

            if( mov != null && oha != null && shouldAttack(mov.entityHit, player) ) {
                oha.swingOffHand(player);
            }

            if( !ConfigurationHandler.refoundEnergy ) {
                EventHandlers.INSTANCE.offhandCooldown = Helpers.getOffhandCooldown(player);
            }

            if( mov != null && mov.entityHit != null ) {
                ISecondHurtTimer sht = mov.entityHit.getCapability(EventHandlers.SECONDHURTTIMER_CAP, null);
                if( sht != null && sht.getHurtTimerBCM() <= 0 ) {
                    if( shouldAttack(mov.entityHit, player) ) {
                        PacketHandler.instance.sendToServer(new PacketOffhandAttack(mov.entityHit.getEntityId()));
                    }
                }
            }
        }
    }

    public static void refoundEnergy(EntityPlayer player) {
        EventHandlers.INSTANCE.giveEnergy = true;
        EventHandlers.INSTANCE.energyToGive = player.ticksSinceLastSwing;
    }

    private static boolean shouldAttack(Entity entHit, EntityPlayer player) {
        if( entHit == null ) {
            return true;
        }

        if( entHit instanceof EntityPlayerMP ) {
            return Helpers.execNullable(entHit.getServer(), MinecraftServer::isPVPEnabled, false);
        }

        return !(entHit instanceof EntityHorse && Helpers.execNullable(((EntityHorse) entHit).getOwnerUniqueId(), uuid -> uuid.equals(player.getUniqueID()), false))
                       && !(entHit instanceof EntityArmorStand)
                       && !(entHit instanceof IEntityOwnable && ((IEntityOwnable) entHit).getOwner() == player)
                       && !(entHit instanceof EntityVillager);
    }


    public static RayTraceResult getMouseOverExtended(double dist) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity rvEntity = mc.getRenderViewEntity();
        if( rvEntity == null ) {
            return null;
        }

        AxisAlignedBB viewBB = new AxisAlignedBB(rvEntity.posX - 0.5D, rvEntity.posY - 0.0D, rvEntity.posZ - 0.5D, rvEntity.posX + 0.5D, rvEntity.posY + 1.5D, rvEntity.posZ + 0.5D);
        if( mc.world != null ) {
            RayTraceResult traceResult = rvEntity.rayTrace(dist, 0.0F);
            final Vec3d pos = rvEntity.getPositionEyes(0.0F);
            final Vec3d lookVec = rvEntity.getLook(0.0F);
            final Vec3d lookTarget = pos.addVector(lookVec.x * dist, lookVec.y * dist, lookVec.z * dist);
            final float growth = 1.0F;
            final List<Entity> list = mc.world.getEntitiesWithinAABBExcludingEntity(rvEntity, viewBB.expand(lookVec.x * dist, lookVec.y * dist, lookVec.z * dist).grow(growth, growth, growth));
            final double calcdist = traceResult != null ? traceResult.hitVec.distanceTo(pos) : dist;

            double newDist = calcdist;
            Entity pointed = null;

            for( Entity entity : list ) {
                if( entity.canBeCollidedWith() ) {
                    float borderSize = entity.getCollisionBorderSize();
                    AxisAlignedBB aabb;

                    if( ConfigurationHandler.widerAttack ) {
                        aabb = new AxisAlignedBB(entity.posX - entity.width * 1.5F, entity.posY, entity.posZ - entity.width * 1.5F, entity.posX + entity.width * 1.5F, entity.posY + entity.height, entity.posZ + entity.width * 1.5F);
                    } else {
                        aabb = new AxisAlignedBB(entity.posX - entity.width / 2.0F, entity.posY, entity.posZ - entity.width / 2.0F, entity.posX + entity.width / 2.0F, entity.posY + entity.height, entity.posZ + entity.width / 2.0F);
                    }

                    aabb.grow(borderSize, borderSize, borderSize);
                    RayTraceResult mop0 = aabb.calculateIntercept(pos, lookTarget);
                    if( aabb.contains(pos) ) {
                        if( newDist >= -0.000001D ) {
                            pointed = entity;
                            newDist = 0.0D;
                        }
                    } else if( mop0 != null ) {
                        double hitDist = pos.distanceTo(mop0.hitVec);
                        if( hitDist < newDist || (newDist >= -0.000001D && newDist <= 0.000001D) ) {
                            pointed = entity;
                            newDist = hitDist;
                        }
                    }
                }
            }

            if( pointed != null && (newDist < calcdist || traceResult == null) ) {
                return new RayTraceResult(pointed);
            }

            return traceResult;
        }

        return null;
    }
}