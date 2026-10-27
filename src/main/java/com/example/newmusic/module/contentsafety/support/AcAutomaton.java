package com.example.newmusic.module.contentsafety.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aho-Corasick 自动机（PRD 里的「DFA + 失败指针」）。
 *
 * <p>一次扫描匹配全部模式，复杂度 O(n + 命中数)，与词库大小无关。
 * 词库构建后只读，可安全地整体替换引用实现双 buffer 热更新。
 */
final class AcAutomaton {

    private final Node root;
    private final int size;

    private AcAutomaton(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    static AcAutomaton build(Collection<Word> words) {
        Node root = new Node();
        int count = 0;
        for (Word word : words) {
            if (word == null || word.getNormalized() == null || word.getNormalized().isEmpty()) {
                continue;
            }
            Node node = root;
            for (int i = 0; i < word.getNormalized().length(); i++) {
                Character c = word.getNormalized().charAt(i);
                node = node.children.computeIfAbsent(c, k -> new Node());
            }
            node.outputs.add(word);
            count++;
        }
        // 失败指针按 BFS 构建；输出链在匹配时沿着 fail 走，命中后缀词。
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                Node child = entry.getValue();
                Node fail = current.fail;
                while (fail != null && !fail.children.containsKey(entry.getKey())) {
                    fail = fail.fail;
                }
                child.fail = fail == null ? root : fail.children.get(entry.getKey());
                queue.add(child);
            }
        }
        return new AcAutomaton(root, count);
    }

    int size() {
        return size;
    }

    List<Hit> findAll(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return Collections.emptyList();
        }
        List<Hit> hits = new ArrayList<>();
        Node node = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            while (node != null && !node.children.containsKey(c)) {
                node = node.fail;
            }
            if (node == null) {
                Node fromRoot = root.children.get(c);
                node = fromRoot == null ? root : fromRoot;
            } else {
                node = node.children.get(c);
            }
            Node output = node;
            while (output != null) {
                for (Word word : output.outputs) {
                    int start = i - word.getNormalized().length() + 1;
                    if (start >= 0) {
                        hits.add(new Hit(word, start, i + 1));
                    }
                }
                output = output.fail;
                if (output == root) {
                    break;
                }
            }
        }
        return hits;
    }

    static final class Node {
        final Map<Character, Node> children = new HashMap<>(4);
        final List<Word> outputs = new ArrayList<>(1);
        Node fail;
    }

    static final class Hit {
        final Word word;
        final int start;
        final int end;

        Hit(Word word, int start, int end) {
            this.word = word;
            this.start = start;
            this.end = end;
        }
    }

    /** 词条：normalized 是归一化后的匹配形式，display 只用于超级管理员界面。 */
    static final class Word {
        private final long id;
        private final String normalized;
        private final String display;
        private final int level;
        private final String category;
        private final String source;

        Word(long id, String normalized, String display, int level, String category, String source) {
            this.id = id;
            this.normalized = normalized;
            this.display = display;
            this.level = level;
            this.category = category;
            this.source = source;
        }

        long getId() {
            return id;
        }

        String getNormalized() {
            return normalized;
        }

        String getDisplay() {
            return display;
        }

        int getLevel() {
            return level;
        }

        String getCategory() {
            return category;
        }

        String getSource() {
            return source;
        }
    }
}
