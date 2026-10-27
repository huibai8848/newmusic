import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { aiApi, aiKeys, type LyricTranslation, type SongEmotion } from '@/api/ai';
import { ApiError } from '@/api/request';
import type { Id } from '@/types/api';
import { ErrCode } from '@/types/api';

/**
 * 歌词翻译与情绪标注的读取/生成（模块 14 P1）。
 *
 * 「不适用」（60006）要单独归成一类状态：中文歌、无歌词都走这个码，
 * 它表示**这首歌不需要这个功能**，不是加载失败。页面据此把入口藏起来，
 * 而不是弹一个红色 toast 告诉用户"失败了"。
 */

function isNotApplicable(error: unknown): boolean {
  return error instanceof ApiError && error.code === ErrCode.AI_NOT_APPLICABLE;
}

export interface UseLyricTranslationResult {
  data: LyricTranslation | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  /** 中文歌 / 无歌词：功能不适用，不展示入口 */
  notApplicable: boolean;
  isGenerating: boolean;
  generate: () => void;
  submitFeedback: (issue: string) => Promise<void>;
}

export function useLyricTranslation(songId: Id | null | undefined): UseLyricTranslationResult {
  const queryClient = useQueryClient();
  const key = aiKeys.translation(songId ?? '');

  const query = useQuery({
    queryKey: key,
    queryFn: () => aiApi.getTranslation(songId as Id),
    enabled: Boolean(songId),
    // 译文生成后基本不变；5 分钟足够，避免每次进页面都打一次接口
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const mutation = useMutation({
    mutationFn: () => aiApi.generateTranslation(songId as Id),
    onSuccess: (data) => {
      // 直接用生成结果覆盖缓存：再请求一次 GET 只会多一次往返
      queryClient.setQueryData(key, data);
    },
  });

  const feedback = useMutation({
    mutationFn: (issue: string) => aiApi.feedback(songId as Id, issue),
  });

  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    notApplicable: isNotApplicable(query.error),
    isGenerating: mutation.isPending,
    generate: () => mutation.mutate(),
    submitFeedback: async (issue: string) => {
      await feedback.mutateAsync(issue);
    },
  };
}

export interface UseSongEmotionResult {
  data: SongEmotion | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
}

/** 情绪/场景标注：纯展示，失败就整块不渲染，不重试（和歌词翻译同样的理由） */
export function useSongEmotion(songId: Id | null | undefined): UseSongEmotionResult {
  const query = useQuery({
    queryKey: aiKeys.emotion(songId ?? ''),
    queryFn: () => aiApi.getEmotion(songId as Id),
    enabled: Boolean(songId),
    staleTime: 30 * 60 * 1000,
    retry: false,
  });

  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}
