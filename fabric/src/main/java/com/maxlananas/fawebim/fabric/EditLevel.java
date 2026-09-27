package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The level a tree, a feature or a structure is generated into: it reads the
 * world as the edit sees it and hands every block it writes to the edit, as
 * WorldEdit's server level proxy does, so the mask, the change limit and the
 * history see what grew. Placed straight into the level, a //forest could not
 * be undone.
 *
 * <p>The methods are told apart by their parameter and return types, which
 * the remapping of the game's names leaves alone: a block and a fluid read at
 * a position, a predicate on either - by the type it tests - a block written
 * at a position, and a block removed or destroyed, which leaves air. A default
 * method runs its own body on this level, so isEmptyBlock and the like read
 * through it too. Everything else - the heights, the random source, the
 * registries - is the level's own.</p>
 *
 * <p>A block written with a block entity gets one of its own here, which is
 * the one the generator is handed when it asks for it: a structure fills its
 * chests' loot tables and its spawners' mobs, a tree its bee nest's bees, into
 * a block the level does not have yet. Closing the level hands their data to
 * the edit. An entity the generator adds - a structure's villagers, its
 * golems - is added through the edit too, so the history takes it back.</p>
 *
 * <p>Server thread only, like the level and the edit.</p>
 */
final class EditLevel implements InvocationHandler, AutoCloseable {

    private final ServerLevel level;
    private final EditSession session;
    private final WorldGenLevel proxy;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

    private EditLevel(ServerLevel level, EditSession session) {
        this.level = level;
        this.session = session;
        this.proxy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, this);
    }

    /** A level writing into the edit, to be closed once the generator is done. */
    static EditLevel open(ServerLevel level, EditSession session) {
        return new EditLevel(level, session);
    }

    /** The level to hand the generator. */
    WorldGenLevel level() {
        return proxy;
    }

    private BlockState read(BlockPos pos) {
        return Block.stateById(session.getBlock(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean write(BlockPos pos, BlockState state) {
        // A generator moves one mutable position around: the key must not move with it.
        BlockPos at = pos.immutable();
        BlockEntity blockEntity = state.hasBlockEntity() && state.getBlock() instanceof EntityBlock block
                ? block.newBlockEntity(at, state) : null;
        if (blockEntity != null) {
            blockEntities.put(at, blockEntity);
        } else {
            blockEntities.remove(at);
        }
        return session.setBlock(at.getX(), at.getY(), at.getZ(), Block.getId(state));
    }

    /** An entity the generator adds, its passengers in its data, spawned through the edit. */
    private boolean add(Entity entity) {
        NbtCompound data = FabricWorld.saveEntity(entity, level.registryAccess());
        if (data == null) {
            return false;
        }
        session.addEntity(new EntityData(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), data,
                new Vector3(entity.getX(), entity.getY(), entity.getZ())));
        return true;
    }

    /** Hands the edit the data of the block entities the generator filled. */
    @Override
    public void close() {
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockPos pos = entry.getKey();
            NbtCompound data = FabricWorld.saveBlockEntity(entry.getValue(), level.registryAccess());
            if (data != null) {
                session.setBlockEntity(pos.getX(), pos.getY(), pos.getZ(), data);
            }
        }
        blockEntities.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?>[] types = method.getParameterTypes();
        Class<?> result = method.getReturnType();
        if (types.length > 0 && types[0] == BlockPos.class) {
            BlockPos pos = (BlockPos) args[0];
            if (types.length == 1 && result == BlockState.class) {
                return read(pos);
            }
            if (types.length == 1 && result == FluidState.class) {
                return read(pos).getFluidState();
            }
            if (types.length == 1 && result == BlockEntity.class) {
                return blockEntities.get(pos);
            }
            if (types.length == 2 && types[1] == BlockEntityType.class && result == Optional.class) {
                BlockEntity found = blockEntities.get(pos);
                return found != null && found.getType() == args[1] ? Optional.of(found) : Optional.empty();
            }
            if (types.length == 2 && types[1] == Predicate.class && result == boolean.class) {
                Type tested = method.getGenericParameterTypes()[1] instanceof ParameterizedType predicate
                        ? predicate.getActualTypeArguments()[0] : null;
                if (tested == BlockState.class) {
                    return ((Predicate<BlockState>) args[1]).test(read(pos));
                }
                if (tested == FluidState.class) {
                    return ((Predicate<FluidState>) args[1]).test(read(pos).getFluidState());
                }
            }
            if (types.length >= 2 && types[1] == BlockState.class && result == boolean.class) {
                return write(pos, (BlockState) args[1]);
            }
            if (types.length >= 2 && types[1] == boolean.class && result == boolean.class) {
                return write(pos, Blocks.AIR.defaultBlockState());
            }
        }
        // addFreshEntity is the one method of a writer taking an entity alone,
        // addFreshEntityWithPassengers the one of a server level accessor.
        if (types.length == 1 && types[0] == Entity.class) {
            Class<?> declaring = method.getDeclaringClass();
            if (result == boolean.class && LevelWriter.class.isAssignableFrom(declaring)) {
                return add((Entity) args[0]);
            }
            if (result == void.class && ServerLevelAccessor.class.isAssignableFrom(declaring)) {
                add((Entity) args[0]);
                return null;
            }
        }
        if (method.isDefault() && keepsDefault(method)) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        try {
            return method.invoke(level, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Whether the level keeps an interface's default body for a method: that
     * body then runs on this level, so what it reads comes through the edit.
     * A method the level overrides stays the level's. The method is looked up
     * by the name it has at run time, whatever the mappings made of it.
     */
    private boolean keepsDefault(Method method) {
        Class<?> type = level.getClass();
        return INHERITED.get(type).computeIfAbsent(method, candidate -> {
            try {
                return type.getMethod(candidate.getName(), candidate.getParameterTypes())
                        .getDeclaringClass().isInterface();
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    /** {@link #keepsDefault} for each class of level, worked out once per method. */
    private static final ClassValue<Map<Method, Boolean>> INHERITED = new ClassValue<>() {
        @Override
        protected Map<Method, Boolean> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
}
