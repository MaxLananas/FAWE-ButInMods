package com.maxlananas.fawebim.core.session;

/**
 * The state of every {@link SideEffect} for one edit, packed two bits per
 * effect the way WorldEdit packs it, so an edit session carries one int rather
 * than a map of enum states.
 */
public final class SideEffectSet {

    private static final SideEffect[] EFFECTS = SideEffect.values();
    private static final SideEffect.State[] STATES = SideEffect.State.values();
    private static final SideEffectSet DEFAULTS = new SideEffectSet(defaultBits());
    private static final SideEffectSet NONE = new SideEffectSet(0);

    private final int bits;

    private SideEffectSet(int bits) {
        this.bits = bits;
    }

    private static int defaultBits() {
        SideEffectSet set = new SideEffectSet(0);
        for (SideEffect effect : SideEffect.values()) {
            set = set.with(effect, effect.getDefaultValue());
        }
        return set.bits;
    }

    /** The effects the engine runs unless a command says otherwise. */
    public static SideEffectSet defaults() {
        return DEFAULTS;
    }

    /** No side effect at all, which is what a raw write wants. */
    public static SideEffectSet none() {
        return NONE;
    }

    public SideEffectSet with(SideEffect effect, SideEffect.State state) {
        int shift = effect.ordinal() * 2;
        return new SideEffectSet((bits & ~(3 << shift)) | (state.ordinal() << shift));
    }

    public SideEffect.State getState(SideEffect effect) {
        return STATES[(bits >>> (effect.ordinal() * 2)) & 3];
    }

    /** Whether the effect runs at all, now or at the end of the edit. */
    public boolean shouldApply(SideEffect effect) {
        return getState(effect) != SideEffect.State.OFF;
    }

    public boolean isDelayed(SideEffect effect) {
        return getState(effect) == SideEffect.State.DELAYED;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (SideEffect effect : EFFECTS) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(effect.lowerName()).append('=').append(getState(effect).lowerName());
        }
        return text.toString();
    }
}
