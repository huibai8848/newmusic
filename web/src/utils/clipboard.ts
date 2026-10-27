/**
 * 复制文本：优先 Clipboard API，失败时退回到隐藏 textarea。
 *
 * 非 HTTPS 页面（局域网联调常见）里 `navigator.clipboard` 可能不存在，
 * 直接调用会抛 TypeError，所以必须保留兜底。
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 权限被拒时继续走兜底
    }
  }
  try {
    const el = document.createElement('textarea');
    el.value = text;
    el.setAttribute('readonly', '');
    el.style.position = 'fixed';
    el.style.opacity = '0';
    document.body.appendChild(el);
    el.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(el);
    return ok;
  } catch {
    return false;
  }
}
