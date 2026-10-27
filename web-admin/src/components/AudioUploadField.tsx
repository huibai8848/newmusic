import { useState } from 'react';
import { Button, Input, Typography, Upload, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { fileApi } from '@/api/endpoints';
import { parseAudioMetadata, type AudioMetadata } from '@/utils/audioMetadata';

/** 与服务端 AdminFileController.MAX_AUDIO、application.yml 的 multipart 上限保持一致 */
const MAX_AUDIO_MB = 50;
const ACCEPTED_EXTENSIONS = ['.mp3', '.wav', '.flac'];

interface AudioUploadFieldProps {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  /** 上传成功后回调音频内嵌标签；只有主音源（标准音质）需要接这个回调 */
  onMetadata?: (metadata: AudioMetadata) => void;
}

/**
 * 音频地址输入 + 上传按钮（后台歌曲表单的标准/高清/无损音质共用）。
 *
 * 上传走 `POST /admin/files/audio`，服务端不转码，返回原始文件的
 * `/media/uploads/...` 地址并回填表单；也可以继续手填外部 URL。
 * 前端只做扩展名与 50MB 预检，真正的格式与文件头校验在服务端。
 */
export function AudioUploadField({
  value,
  onChange,
  placeholder = '上传音频，或直接填写音频 URL',
  disabled = false,
  onMetadata,
}: AudioUploadFieldProps): JSX.Element {
  const [uploading, setUploading] = useState(false);
  const current = value ?? '';

  const handleFile = async (file: File) => {
    const name = file.name.toLowerCase();
    if (!ACCEPTED_EXTENSIONS.some((ext) => name.endsWith(ext))) {
      message.error('仅支持 MP3 / WAV / FLAC 音频');
      return;
    }
    if (file.size <= 0) {
      message.error('音频文件是空的');
      return;
    }
    if (file.size > MAX_AUDIO_MB * 1024 * 1024) {
      message.error(
        `音频不能超过 ${MAX_AUDIO_MB}MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）`,
      );
      return;
    }
    setUploading(true);
    // 解析与上传并行：读标签不影响上传耗时，失败也不阻断上传
    const metadataTask = parseAudioMetadata(file);
    try {
      const result = await fileApi.uploadAudio(file);
      onChange?.(result.url);
      message.success('音频上传成功，已填入地址');
      const metadata = await metadataTask;
      if (metadata && onMetadata) {
        onMetadata(metadata);
      }
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Input
          value={current}
          onChange={(event) => onChange?.(event.target.value)}
          placeholder={placeholder}
          allowClear
          disabled={disabled}
        />
        <Upload
          accept=".mp3,.wav,.flac"
          showUploadList={false}
          disabled={disabled || uploading}
          beforeUpload={(file) => {
            void handleFile(file);
            // 交给 handleFile 走后台接口，禁止 antd 再发一次默认上传
            return Upload.LIST_IGNORE;
          }}
        >
          <Button icon={<UploadOutlined />} loading={uploading} disabled={disabled}>
            {uploading ? '上传中…' : '上传音频'}
          </Button>
        </Upload>
      </div>
      {current ? (
        <audio
          controls
          preload="none"
          src={current}
          style={{ display: 'block', width: '100%', height: 36, marginTop: 8 }}
        />
      ) : null}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        支持 MP3 / WAV / FLAC，单个 ≤ {MAX_AUDIO_MB}MB；不做转码，上传成功后自动填入地址
      </Typography.Text>
    </div>
  );
}
