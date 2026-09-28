package com.maxlananas.fawebim.core.math;

import java.util.List;

/**
 * A Kochanek-Bartels spline through a list of nodes, as WorldEdit's
 * KochanekBartelsInterpolation computes it; tension, bias and continuity of 0
 * make it a Catmull-Rom spline.
 *
 * <p>The position runs from 0 at the first node to 1 at the last, every
 * segment taking the same share of it whatever its length, and each end
 * repeats its node as its outer neighbour.</p>
 */
public final class KochanekBartels {

    private final Vector3[] a;
    private final Vector3[] b;
    private final Vector3[] c;
    private final Vector3[] d;
    private final int scaling;

    public KochanekBartels(List<Vector3> nodes, double tension, double bias, double continuity) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("A spline needs a node");
        }
        int count = nodes.size();
        a = new Vector3[count];
        b = new Vector3[count];
        c = new Vector3[count];
        d = new Vector3[count];
        double ta = (1 - tension) * (1 + bias) * (1 + continuity) / 2;
        double tb = (1 - tension) * (1 - bias) * (1 - continuity) / 2;
        double tc = (1 - tension) * (1 + bias) * (1 - continuity) / 2;
        double td = (1 - tension) * (1 - bias) * (1 + continuity) / 2;
        for (int i = 0; i < count; i++) {
            a[i] = combine(nodes, i, -ta, ta - tb - tc + 2, tb + tc - td - 2, td);
            b[i] = combine(nodes, i, 2 * ta, -2 * ta + 2 * tb + tc - 3, -2 * tb - tc + td + 3, -td);
            c[i] = combine(nodes, i, -ta, ta - tb, tb, 0);
            d[i] = node(nodes, i);
        }
        scaling = count - 1;
    }

    private static Vector3 combine(List<Vector3> nodes, int index, double f1, double f2, double f3, double f4) {
        return node(nodes, index - 1).multiply(f1).add(node(nodes, index).multiply(f2))
                .add(node(nodes, index + 1).multiply(f3)).add(node(nodes, index + 2).multiply(f4));
    }

    private static Vector3 node(List<Vector3> nodes, int index) {
        return nodes.get(Math.max(0, Math.min(nodes.size() - 1, index)));
    }

    /** The point at {@code position}, from 0 to 1. */
    public Vector3 position(double position) {
        if (!(position >= 0 && position <= 1)) {
            throw new IllegalArgumentException("A spline position runs from 0 to 1, not " + position);
        }
        double scaled = position * scaling;
        int index = (int) Math.floor(scaled);
        double remainder = scaled - index;
        return a[index].multiply(remainder).add(b[index]).multiply(remainder).add(c[index]).multiply(remainder)
                .add(d[index]);
    }

    /** The length of the whole spline, summed the way WorldEdit sums it. */
    public double arcLength() {
        double length = 0;
        for (int index = 0; index < scaling; index++) {
            length += segmentLength(index);
        }
        return length;
    }

    private double segmentLength(int index) {
        Vector3 a3 = a[index].multiply(3.0);
        Vector3 b2 = b[index].multiply(2.0);
        Vector3 c1 = c[index];
        int points = 8;
        double sum = c1.length() / 2.0;
        // WorldEdit's sum stops at the sixth inner point of eight; the count of
        // blocks a curve is walked in follows from it.
        for (int i = 1; i < points - 1; i++) {
            double t = (double) i / points;
            sum += a3.multiply(t).add(b2).multiply(t).add(c1).length();
        }
        sum += a3.add(b2).add(c1).length() / 2.0;
        return sum / points;
    }
}
