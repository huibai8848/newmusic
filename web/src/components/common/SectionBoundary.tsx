import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, Result } from 'antd';

/**
 * 区块级错误边界（模块 02 D-10 / PRD BR-11-002）。
 *
 * 首页各区块独立请求，任一区块渲染异常只影响自己，其余区块照常展示（AC-11-204）。
 * React 的错误边界必须是 class 组件——这是官方 API 的限制，不是风格选择。
 */

interface Props {
  /** 区块名，用于提示文案与日志定位 */
  title?: string;
  children: ReactNode;
}

interface State {
  hasError: boolean;
  message?: string;
}

export class SectionBoundary extends Component<Props, State> {
  override state: State = { hasError: false };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error.message };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error(`[SectionBoundary:${this.props.title ?? 'unknown'}]`, error, info.componentStack);
  }

  private readonly handleRetry = (): void => {
    this.setState({ hasError: false, message: undefined });
  };

  override render(): ReactNode {
    if (!this.state.hasError) return this.props.children;

    return (
      <Result
        status="warning"
        title={`${this.props.title ?? '该区块'}加载失败`}
        subTitle={this.state.message}
        extra={
          <Button type="primary" onClick={this.handleRetry}>
            重新加载
          </Button>
        }
      />
    );
  }
}
