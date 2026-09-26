package com.maxlananas.fawebim.core.session;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.transform.Transform;

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
