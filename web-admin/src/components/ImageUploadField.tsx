import { useState } from 'react';
import { Button, Image, Input, Typography, Upload, message } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { fileApi } from '@/api/endpoints';

const MAX_IMAGE_MB = 5;
const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

interface ImageUploadFieldProps {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  /** 预览缩略图边长（像素） */
  previewSize?: number;
}

/**
 * 图片地址输入 + 上传按钮（后台歌曲封面、歌手头像/封面共用）。
 *
 * 既可手填已有 URL（对象存储、历史素材），也可直接选本地图片调
 * `POST /admin/files/image` 上传，成功后把返回的 `/media/uploads/...` 填回表单。
 * 这里只做大小/类型的前端预检，真正的图片类型由服务端按文件头识别。
 */
export function ImageUploadField({
  value,
  onChange,
  placeholder = '上传图片，或直接填写图片 URL',
  disabled = false,
  previewSize = 96,
}: ImageUploadFieldProps): JSX.Element {
  const [uploading, setUploading] = useState(false);
  // 后端历史数据里 cover/avatar 可能是 null，直接传给 Input 会触发受控组件告警
  const current = value ?? '';

  const handleFile = async (file: File) => {
    if (!ACCEPTED_TYPES.includes(file.type)) {
      message.error('仅支持 JPG / PNG / WebP 图片');
      return;
    }
    if (file.size > MAX_IMAGE_MB * 1024 * 1024) {
      message.error(
        `图片不能超过 ${MAX_IMAGE_MB}MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）`,
      );
      return;
    }
    setUploading(true);
    try {
      const result = await fileApi.uploadImage(file);
      onChange?.(result.url);
      message.success('图片上传成功');
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
          accept={ACCEPTED_TYPES.join(',')}
          showUploadList={false}
          disabled={disabled || uploading}
          beforeUpload={(file) => {
            void handleFile(file);
            // 交给上面的 handleFile 走后台接口，禁止 antd 再发一次默认上传
            return Upload.LIST_IGNORE;
          }}
        >
          <Button icon={<PictureOutlined />} loading={uploading} disabled={disabled}>
            上传图片
          </Button>
        </Upload>
      </div>
      {current ? (
        <div style={{ marginTop: 8 }}>
          <Image
            src={current}
            alt="图片预览"
            width={previewSize}
            height={previewSize}
            style={{
              objectFit: 'cover',
              borderRadius: 6,
              border: '1px solid #f0f0f0',
              background: '#fafafa',
            }}
          />
        </div>
      ) : null}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        支持 JPG / PNG / WebP，单张 ≤ {MAX_IMAGE_MB}MB；上传成功后自动填入地址
      </Typography.Text>
    </div>
  );
}
