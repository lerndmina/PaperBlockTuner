package io.github.lumine1909.blocktuner.network;

import io.github.lumine1909.blocktuner.data.NoteBlockData;
import io.github.lumine1909.blocktuner.object.Instrument;
import io.github.lumine1909.blocktuner.util.InstrumentUtil;
import io.github.lumine1909.blocktuner.util.NoteUtil;
import io.github.lumine1909.blocktuner.util.TuneUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import static io.github.lumine1909.blocktuner.BlockTunerPlugin.plugin;
import static io.github.lumine1909.blocktuner.util.ReflectionUtil.set;

public class BlockTunerProtocol implements PluginMessageListener {

    public static final String MOD_ID = "blocktuner";
    public static final String CLIENT_BOUND_HELLO = "blocktuner:client_bound_hello";
    public static final String SERVER_BOUND_HELLO = "blocktuner:server_bound_hello";
    public static final String SERVER_BOUND_TUNING = "blocktuner:server_bound_tuning";
    private static final int TUNING_PROTOCOL = 3;
    private static final String HANDLER_NAME = "blocktuner_pick_handler";

    @SuppressWarnings("deprecation")
    private static void addBlockDataToItem(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        if (blockEntity != null) {
            TagValueOutput tagValueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            blockEntity.saveCustomOnly(tagValueOutput);
            blockEntity.removeComponentsFromTag(tagValueOutput);
            BlockItem.setBlockEntityData(stack, blockEntity.getType(), tagValueOutput);
            stack.applyComponents(blockEntity.collectComponents());
        }
    }

    private static void tryPickItem(ItemStack stack, ServerPlayer player) {
        if (!stack.isItemEnabled(player.level().enabledFeatures())) {
            return;
        }
        Inventory inventory = player.getInventory();
        int sourceSlot = inventory.findSlotMatchingItem(stack);
        int targetSlot = Inventory.isHotbarSlot(sourceSlot) ? sourceSlot : inventory.getSuitableHotbarSlot();
        if (sourceSlot != -1) {
            if (Inventory.isHotbarSlot(sourceSlot) && Inventory.isHotbarSlot(targetSlot)) {
                set(inventory.getClass(), "selected", inventory, targetSlot);
            } else {
                inventory.pickSlot(sourceSlot, targetSlot);
            }
        } else if (player.hasInfiniteMaterials()) {
            inventory.addAndPickItem(stack, targetSlot);
        }
        player.connection.send(new ClientboundSetHeldSlotPacket(targetSlot));
        player.inventoryMenu.broadcastChanges();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        if (channel.equals(SERVER_BOUND_HELLO)) {
            int protocolVersion = buf.readInt();
            if (protocolVersion == TUNING_PROTOCOL) {
                player.sendPluginMessage(plugin, CLIENT_BOUND_HELLO, message);
            }
        } else if (channel.equals(SERVER_BOUND_TUNING)) {
            BlockPos pos = buf.readBlockPos();
            int note = buf.readInt();
            Level world = serverPlayer.level();
            if (world.getBlockState(pos).getBlock() == Blocks.NOTE_BLOCK) {
                Bukkit.getScheduler().runTask(plugin, () -> TuneUtil.tune(serverPlayer, (ServerLevel) world, pos, NoteUtil.byNote(note), Instrument.DEFAULT));
            }
        }
    }

    public void injectPlayer(Player player) {
        try {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new PickItemHandler(((CraftPlayer) player).getHandle()));
            }
        } catch (Exception ignored) {
        }
    }

    public void uninjectPlayer(Player player) {
        try {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Exception ignored) {
        }
    }

    private class PickItemHandler extends ChannelDuplexHandler {

        private final ServerPlayer serverPlayer;

        PickItemHandler(ServerPlayer serverPlayer) {
            this.serverPlayer = serverPlayer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundPickItemFromBlockPacket packet && packet.includeData()) {
                ServerLevel serverLevel = serverPlayer.level();
                BlockPos blockPos = packet.pos();
                if (!serverPlayer.isRemoved() && serverPlayer.isWithinBlockInteractionRange(blockPos, 1.0F) && serverLevel.isLoaded(blockPos)) {
                    BlockState blockState = serverLevel.getBlockState(blockPos);
                    if (blockState.getBlock() instanceof NoteBlock) {
                        boolean flag = serverPlayer.hasInfiniteMaterials();
                        ItemStack cloneItemStack = blockState.getCloneItemStack(serverLevel, blockPos, flag);
                        if (!cloneItemStack.isEmpty()) {
                            if (flag && serverPlayer.getBukkitEntity().hasPermission("minecraft.nbt.copy")) {
                                addBlockDataToItem(blockState, serverLevel, blockPos, cloneItemStack);
                            }
                            final ItemStack toPick;
                            if (flag) {
                                org.bukkit.inventory.ItemStack bukkitItemStack = CraftItemStack.asBukkitCopy(cloneItemStack);
                                NoteBlockInstrument instrument = blockState.getValue(NoteBlock.INSTRUMENT);
                                Integer note = blockState.getValue(NoteBlock.NOTE);
                                NoteBlockData data = new NoteBlockData(note, InstrumentUtil.byMcName(instrument.name().toLowerCase()));
                                bukkitItemStack = data.apply(bukkitItemStack);
                                toPick = CraftItemStack.asNMSCopy(bukkitItemStack);
                            } else {
                                toPick = cloneItemStack;
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!serverPlayer.isRemoved()) {
                                    tryPickItem(toPick, serverPlayer);
                                }
                            });
                        }
                        return;
                    }
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}