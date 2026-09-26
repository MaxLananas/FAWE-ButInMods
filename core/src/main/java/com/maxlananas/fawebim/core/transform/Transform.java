package com.maxlananas.fawebim.core.transform;

import com.maxlananas.fawebim.core.math.Vector3;

/**
 * FAWE's transform pipeline: the {@code /transform} command chains
 * {@code rotate}/{@code flip}/{@code scale}/{@code offset} and applies the
 * result to pasted clipboards, brushes and {@code //gtransform}.
 */
public interface Transform {

    Vector3 apply(Vector3 point);

    /**
     * Where a direction points once transformed: the linear part of the
     * transform, which turns a block's facing, axis or rotation. For an affine
     * transform it is {@code apply(d) - apply(0)}; a transform whose offset
     * varies from one call to the next says so by overriding this.
     */
    default Vector3 applyDirection(Vector3 direction) {
        return apply(direction).subtract(apply(Vector3.ZERO));
    }

    default boolean isIdentity() {
        return false;
    }

    /** Composes this transform with another (this first, then {@code next}). */
    default Transform combine(Transform next) {
        Transform self = this;
        return new Transform() {
            @Override
            public Vector3 apply(Vector3 point) {
                return next.apply(self.apply(point));
            }

            @Override
            public Vector3 applyDirection(Vector3 direction) {
                return next.applyDirection(self.applyDirection(direction));
            }

            @Override
            public boolean isIdentity() {
                return self.isIdentity() && next.isIdentity();
            }
        };
    }

    static Transform identity() {
        return new Transform() {
            @Override
            public Vector3 apply(Vector3 point) {
                return point;
            }

            @Override
            public Vector3 applyDirection(Vector3 direction) {
                return direction;
            }

            @Override
            public boolean isIdentity() {
                return true;
            }
        };
    }
}
