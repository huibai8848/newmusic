import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Input, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { opsApi, type OpsRunbook } from '@/api/ops';

/**
 * 应急预案：按告警规则里的 runbookKey 关联。
 *
 * 预案是「给凌晨三点值班的人看的」——症状、判定、处置步骤、升级路径各占一段，
 * 所以用展开行展示全文，而不是塞进一个 tooltip。
 */
export function RunbookPanel(): JSX.Element {
  const [severity, setSeverity] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const query = useQuery({
    queryKey: ['ops-runbooks', severity],
    queryFn: () => opsApi.runbooks(severity),
  });

  const data = (query.data ?? []).filter((item) => {
    const text = `${item.runbookKey} ${item.title} ${item.symptom}`.toLowerCase();
    return !keyword || text.includes(keyword.toLowerCase());
  });

  const columns: ColumnsType<OpsRunbook> = [
    {
      title: 'Key',
      dataIndex: 'runbookKey',
      width: 160,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    { title: '预案', dataIndex: 'title', width: 240 },
    {
      title: '级别',
      dataIndex: 'severity',
      width: 80,
      render: (value: string) => (
        <Tag color={value === 'P0' ? 'red' : value === 'P1' ? 'volcano' : 'gold'}>{value}</Tag>
      ),
    },
    { title: '症状', dataIndex: 'symptom', ellipsis: true },
    { title: '负责人', dataIndex: 'owner', width: 130, render: (value: string | null) => value || '—' },
  ];

  return (
    <Card
      size="small"
      title="应急预案"
      extra={
        <Space>
          <Input.Search
            allowClear
            placeholder="搜索 key / 标题 / 症状"
            style={{ width: 220 }}
            onSearch={setKeyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Select
            allowClear
            placeholder="级别"
            style={{ width: 100 }}
            value={severity}
            onChange={setSeverity}
            options={['P0', 'P1', 'P2', 'P3'].map((value) => ({ value, label: value }))}
          />
        </Space>
      }
    >
      <Table
        rowKey="runbookKey"
        size="small"
        loading={query.isLoading}
        dataSource={data}
        columns={columns}
        pagination={false}
        expandable={{
          expandedRowRender: (row) => (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text strong>症状与判定</Typography.Text>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {row.symptom}
              </Typography.Paragraph>
              <Typography.Text strong>处置步骤</Typography.Text>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {row.steps}
              </Typography.Paragraph>
            </Space>
          ),
        }}
      />
    </Card>
  );
}
