package com.example.newmusic.common.util;

import cn.hutool.core.util.StrUtil;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汉字 → 拼音的纯推导工具（模块 06 D-35）。
 *
 * <p><b>为什么在 {@code common/util} 而不是在搜索模块里。</b>拼音列是**曲库写路径**物化的
 * （{@code SongServiceImpl} / {@code ArtistServiceImpl} / {@code AlbumServiceImpl} 在
 * 新增与改名时都要写），而消费它的是**搜索读路径**。若把这段推导留在
 * {@code module/search} 里，就等于让「曲库」依赖「搜索」——一个方向相反的依赖，
 * 后果是曲库模块没法独立编译、也没法独立测试。
 * 它本身又只是一段无状态字符串处理，没有任何搜索语义，正是 {@code common/util} 的用途。
 *
 * <p><b>搜索专属的那部分留在 {@code module.search.support.PinyinSupport}</b>：
 * 「关键词含中文时拼音列不参与检索、用 {@code null} 表达这件事」是搜索的规则，
 * 不是拼音的规则，不该下沉到这里。
 *
 * <p><b>多音字取第一个读音。</b>pinyin4j 返回的顺序不保证「最常用」优先，
 * 例如「长」可能给出 chang/zhang。真正的消歧需要词表，PRD 04 里的 {@code t_pinyin_dict}
 * 就是为此预留的，属模块 11。当前取第一个是本项目的既定口径，登记在系分 §9：
 * 代价是「重庆」可能物化成 {@code zhongqing}，用户敲 {@code chongqing} 搜不到。
 */
public final class PinyinUtil {

    private static final HanyuPinyinOutputFormat FORMAT = buildFormat();

    /**
     * 单字 → 拼音（小写、无声调、{@code ü} 写作 {@code v}）的缓存。
     *
     * <p>回填要对每一行的每个字调用一次 pinyin4j，而它内部要查资源表、做格式校验，
     * 单字级别并不便宜。汉字总量是有限的（常用字约 7000），缓存命中率极高。
     */
    private static final Map<Character, String> CHAR_CACHE = new ConcurrentHashMap<>(8192);

    /**
     * 模块 11 的多音字词典（词 → 拼音）。pinyin4j 只能按单字给读音，且多音字取第一个，
     * 「重庆」会被物化成 {@code zhongqing}。词典由 {@code DictSnapshotService} 在启动/变更时灌入，
     * 这里保持无依赖：{@code common} 不能反向依赖业务模块。
     */
    private static volatile Map<String, String> WORD_OVERRIDES = Collections.emptyMap();
    private static volatile int OVERRIDE_MAX_LENGTH = 0;

    private PinyinUtil() {
    }

    /** 替换多音字词典。传 null/空表示清空。 */
    public static void setWordOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            WORD_OVERRIDES = Collections.emptyMap();
            OVERRIDE_MAX_LENGTH = 0;
            return;
        }
        Map<String, String> copy = new HashMap<>(overrides);
        int max = 0;
        for (String word : copy.keySet()) {
            if (word != null && word.length() > max) {
                max = word.length();
            }
        }
        OVERRIDE_MAX_LENGTH = max;
        WORD_OVERRIDES = Collections.unmodifiableMap(copy);
    }

    private static HanyuPinyinOutputFormat buildFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        // 无声调是硬要求：用户不会输入声调符号，存带声调的拼音等于永远搜不到
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        // 「绿」输出 lv 而不是 lü —— 与「用户会敲 lv」的直觉一致
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    /**
     * 全拼，无分隔（{@code 周杰伦 → zhoujielun}）。
     *
     * <p>非汉字字符：ASCII 字母数字原样保留并转小写，其余（空格、标点）直接丢弃——
     * 存进来的拼音列要能跟「用户敲的、没有空格的拼音」直接做 {@code LIKE}。
     *
     * @return 空串表示「没有任何可用的拼音字符」，调用方应按「无拼音」处理
     */
    public static String fullPinyin(String text) {
        return fullPinyin(text, true);
    }

    /** 只用 pinyin4j、忽略多音字词典。词典服务自身需要它来算「旧值 → 新值」的别名。 */
    public static String rawFullPinyin(String text) {
        return fullPinyin(text, false);
    }

    private static String fullPinyin(String text, boolean useOverrides) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            if (useOverrides) {
                int len = overrideLengthAt(text, i);
                if (len > 0) {
                    String override = WORD_OVERRIDES.get(text.substring(i, i + len));
                    if (override != null) {
                        sb.append(override);
                        i += len - 1;
                        continue;
                    }
                }
            }
            char c = text.charAt(i);
            String pinyin = pinyinOf(c);
            if (pinyin != null) {
                sb.append(pinyin);
            } else if (isAsciiAlphanumeric(c)) {
                sb.append(Character.toLowerCase(c));
            }
            // 其余字符丢弃
        }
        return sb.toString();
    }

    /**
     * 首字母（{@code 周杰伦 → zjl}，{@code 突然好想你 → trhxn}）。
     *
     * <p>与 {@link #fullPinyin} 的差别只在「取整个音节还是只取首个字母」。
     * ASCII 字母数字**逐个保留**（{@code A-Lin → alin}），因为对英文名来说
     * 「首字母」这个降维没有意义：{@code alin} 已经是它最短的可搜形式。
     */
    public static String firstLetters(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int overrideLength = overrideLengthAt(text, i);
            if (overrideLength > 0) {
                String override = WORD_OVERRIDES.get(text.substring(i, i + overrideLength));
                if (override != null) {
                    if (!override.isEmpty()) {
                        sb.append(override.charAt(0));
                    }
                    i += overrideLength - 1;
                    continue;
                }
            }
            char c = text.charAt(i);
            String pinyin = pinyinOf(c);
            if (pinyin != null && !pinyin.isEmpty()) {
                sb.append(pinyin.charAt(0));
            } else if (isAsciiAlphanumeric(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 最长匹配的词长度；调用方随后用同一个长度推进下标。 */
    private static int overrideLengthAt(String text, int start) {
        int max = Math.min(OVERRIDE_MAX_LENGTH, text.length() - start);
        for (int len = max; len >= 1; len--) {
            if (WORD_OVERRIDES.containsKey(text.substring(start, start + len))) {
                return len;
            }
        }
        return 0;
    }

    /** 取单字拼音（小写、无声调）；非汉字返回 {@code null}。 */
    private static String pinyinOf(char c) {
        String cached = CHAR_CACHE.get(c);
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent 在映射函数里抛异常时不会写入，且本函数不会抛——
        // 但 pinyin4j 的 checked 异常必须在这里就地消化，故用显式判断
        String pinyin = resolvePinyin(c);
        if (pinyin != null) {
            CHAR_CACHE.put(c, pinyin);
        }
        return pinyin;
    }

    private static String resolvePinyin(char c) {
        String[] candidates;
        try {
            candidates = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            // FORMAT 是常量且已构造成功，运行期不可能走到这里；真走到了也不该让调用方 500
            return null;
        }
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        // 多音字取第一个读音，见类注释
        return candidates[0].toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
