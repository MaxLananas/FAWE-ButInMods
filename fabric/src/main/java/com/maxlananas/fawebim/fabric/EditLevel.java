package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.extent.EditSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Map;
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
 * registries, the block entities, the entities - is the level's own.</p>
 *
 * <p>Server thread only, like the level and the edit.</p>
 */
final class EditLevel implements InvocationHandler {

    private final ServerLevel level;
    private final EditSession session;

    private EditLevel(ServerLevel level, EditSession session) {
        this.level = level;
        this.session = session;
    }

    static WorldGenLevel of(ServerLevel level, EditSession session) {
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, new EditLevel(level, session));
    }

    private BlockState read(BlockPos pos) {
        return Block.stateById(session.getBlock(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean write(BlockPos pos, BlockState state) {
        return session.setBlock(pos.getX(), pos.getY(), pos.getZ(), Block.getId(state));
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
     * A method the level overrides - addFreshEntity, which the default leaves
     * a no-op - stays the level's. The method is looked up by the name it has
     * at run time, whatever the mappings made of it.
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
