import type { MessageInstance } from 'antd/es/message/interface';
import type { ModalStaticFunctions } from 'antd/es/modal/confirm';
import type { NotificationInstance } from 'antd/es/notification/interface';

/**
 * AntD 5 的静态方法（`message.error`）无法消费 `ConfigProvider` 的主题上下文，
 * 会在暗黑模式下报控制台警告。这里由 `<App>` 在挂载时把上下文实例注入进来，
 * 请求层等「非组件」代码统一走本模块，保证主题一致。
 */

let messageApi: MessageInstance | null = null;
let notificationApi: NotificationInstance | null = null;
let modalApi: Omit<ModalStaticFunctions, 'warn'> | null = null;

export function registerFeedback(api: {
  message: MessageInstance;
  notification: NotificationInstance;
  modal: Omit<ModalStaticFunctions, 'warn'>;
}): void {
  messageApi = api.message;
  notificationApi = api.notification;
  modalApi = api.modal;
}

/** 兜底：`<App>` 尚未挂载时退化到 console，避免直接崩溃 */
export function toastError(content: string): void {
  if (messageApi) messageApi.error(content);
  else console.error('[feedback]', content);
}

export function toastSuccess(content: string): void {
  if (messageApi) messageApi.success(content);
  else console.info('[feedback]', content);
}

export function toastWarning(content: string): void {
  if (messageApi) messageApi.warning(content);
  else console.warn('[feedback]', content);
}

export function toastInfo(content: string): void {
  if (messageApi) messageApi.info(content);
  else console.info('[feedback]', content);
}

export function getNotificationApi(): NotificationInstance | null {
  return notificationApi;
}

export function getModalApi(): Omit<ModalStaticFunctions, 'warn'> | null {
  return modalApi;
}
