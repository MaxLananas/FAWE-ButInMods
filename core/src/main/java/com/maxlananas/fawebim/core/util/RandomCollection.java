package com.maxlananas.fawebim.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Weighted random collection, used by every "25%stone,75%dirt" pattern. */
public final class RandomCollection<E> {

    private final List<E> values = new ArrayList<>();
    private final List<Double> weights = new ArrayList<>();
    private double total;

    public void add(double weight, E value) {
        if (weight <= 0) {
            return;
        }
        values.add(value);
        weights.add(total + weight);
        total += weight;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public double totalWeight() {
        return total;
    }

    public E next(Random random) {
        if (values.isEmpty()) {
            return null;
        }
        double target = random.nextDouble() * total;
        int idx = java.util.Collections.binarySearch(weights, target);
        if (idx < 0) {
            idx = -idx - 1;
        }
        if (idx >= values.size()) {
            idx = values.size() - 1;
        }
        return values.get(idx);
    }

    public E get(int index) {
        return values.get(index);
    }

    public double getWeight(int index) {
        return weights.get(index) - (index == 0 ? 0 : weights.get(index - 1));
    }

    public E getRandom(Random random) {
        return values.get(random.nextInt(values.size()));
    }

    public List<E> values() {
        return values;
    }

    public void clear() {
        values.clear();
        weights.clear();
        total = 0;
    }
}
