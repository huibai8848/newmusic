package com.example.newmusic.module.recupgrade.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MurmurHash3 x86_32 单测。
 *
 * <p>“hello” 的期望值来自 MurmurHash3 参考实现（种子 0），不是本实现跑出来再抄回去：
 * 分桶一旦和 Java 侧不一致，实验报告会悄悄错位，且没有任何报错。
 */
@DisplayName("Murmur3 分桶哈希")
class Murmur3Test {

    @Test
    @DisplayName("标准向量：空串为 0，hello 为 613153351")
    void knownVectors() {
        assertEquals(0, Murmur3.hash32(""));
        assertEquals(613153351, Murmur3.hash32("hello"));
        assertEquals(-156908512, Murmur3.hash32("foo"));
    }

    @Test
    @DisplayName("同输入稳定、不同输入大概率不同")
    void deterministic() {
        assertEquals(Murmur3.hash32("user-42|rank_layer_v1"), Murmur3.hash32("user-42|rank_layer_v1"));
        assertTrue(Murmur3.hash32("user-1") != Murmur3.hash32("user-2"));
    }

    @Test
    @DisplayName("bucket 落在 [0,10000)")
    void bucketRange() {
        for (int i = 0; i < 1000; i++) {
            int bucket = Murmur3.bucket("experiment|" + i + "|salt");
            assertTrue(bucket >= 0 && bucket < 10000, "bucket 越界：" + bucket);
        }
    }

    @Test
    @DisplayName("bucket 对负数哈希取绝对值后再取模")
    void bucketHandlesNegativeHash() {
        assertEquals(Math.floorMod(Murmur3.hash32("foo") & 0x7fffffff, 10000), Murmur3.bucket("foo"));
    }
}
