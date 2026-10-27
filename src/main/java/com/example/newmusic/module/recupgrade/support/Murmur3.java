package com.example.newmusic.module.recupgrade.support;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 x86 32-bit（seed = 0）。
 *
 * <p>实验分流要求「同一 userId + experimentId 永远同一组」（BR-24-073）。
 * 用 JDK 的 {@code String.hashCode()} 也能做到确定性，但它的低位分布很差，
 * 直接取模会让分组比例明显偏离配置；所以这里实现标准的 murmur3。
 *
 * <p>没有引入 Guava：项目依赖里没有它，为一个 30 行的哈希函数加一个几 MB 的
 * 依赖不划算。实现与 Guava {@code Hashing.murmur3_32()} 的结果一致
 * （{@code hash("hello") == 613153351}），单测覆盖了这一点。
 */
public final class Murmur3 {

    private Murmur3() {
    }

    public static int hash32(String value) {
        if (value == null) {
            return 0;
        }
        return hash32(value.getBytes(StandardCharsets.UTF_8), 0);
    }

    public static int hash32(byte[] data, int seed) {
        int length = data.length;
        int h1 = seed;
        int roundedEnd = length & 0xfffffffc;

        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 |= data[roundedEnd] & 0xff;
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= length;

        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    /**
     * 0~9999 的分桶号。
     *
     * <p>用 {@code & 0x7fffffff} 而不是 {@code Math.abs}：{@code Math.abs(Integer.MIN_VALUE)}
     * 仍是负数，会让 bucket 变成 -8，进而把用户分到一个不存在的组。
     */
    public static int bucket(String value) {
        return (hash32(value) & 0x7fffffff) % 10000;
    }
}
