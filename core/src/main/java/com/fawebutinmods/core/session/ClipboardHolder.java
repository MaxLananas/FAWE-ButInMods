package com.fawebutinmods.core.session;

import com.fawebutinmods.core.clipboard.BlockArrayClipboard;
import com.fawebutinmods.core.transform.Transform;

/** The clipboard plus the transform applied on paste (FAWE's {@code ClipboardHolder}). */
public final class ClipboardHolder {

    private final BlockArrayClipboard clipboard;
    private Transform transform = Transform.identity();

    public ClipboardHolder(BlockArrayClipboard clipboard) {
        this.clipboard = clipboard;
    }

    public BlockArrayClipboard getClipboard() {
        return clipboard;
    }

    public Transform getTransform() {
        return transform;
    }

    public void setTransform(Transform transform) {
        this.transform = transform == null ? Transform.identity() : transform;
    }
}
