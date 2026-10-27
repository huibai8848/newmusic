import { Button, Result } from 'antd';
import { ApiError } from '@/api/request';
import { ErrCode } from '@/types/api';

/** 区块/页面级失败态（PRD §6.3：图标 + 文案 + 重试按钮） */

interface Props {
  title?: string;
  error?: unknown;
  onRetry?: () => void;
  compact?: boolean;
}

/** 把错误对象翻译成用户能看懂的一句话 */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === ErrCode.SONG_NOT_FOUND) return '内容不存在或已下架';
    if (error.code === ErrCode.PLAYLIST_NOT_FOUND) return '歌单不存在或无权访问';
    return error.traceId ? `${error.message}（traceId: ${error.traceId}）` : error.message;
  }
  if (error instanceof Error) return error.message;
  return '加载失败，请稍后重试';
}

export function ErrorState({ title = '加载失败', error, onRetry, compact = false }: Props): JSX.Element {
  return (
    <Result
      status="warning"
      title={title}
      subTitle={describeError(error)}
      style={compact ? { padding: '16px 0' } : undefined}
      extra={
        onRetry ? (
          <Button type="primary" onClick={onRetry}>
            重试
          </Button>
        ) : undefined
      }
    />
  );
}
