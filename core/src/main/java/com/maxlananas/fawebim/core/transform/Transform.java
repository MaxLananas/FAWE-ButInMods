package com.maxlananas.fawebim.core.transform;

import com.maxlananas.fawebim.core.math.Vector3;

/**
 * FAWE's transform pipeline: the {@code /transform} command chains
 * {@code rotate}/{@code flip}/{@code scale}/{@code offset} and applies the
 * result to pasted clipboards, brushes and {@code //gtransform}.
 */
public interface Transform {

    Vector3 apply(Vector3 point);

    default boolean isIdentity() {
        return false;
    }

    /** Composes this transform with another (this first, then {@code next}). */
    default Transform combine(Transform next) {
        Transform self = this;
        return point -> next.apply(self.apply(point));
    }

    static Transform identity() {
        return new Transform() {
            @Override
            public Vector3 apply(Vector3 point) {
                return point;
            }

            @Override
            public boolean isIdentity() {
                return true;
            }
        };
    }
}
