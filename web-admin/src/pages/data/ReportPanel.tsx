import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Empty,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { dataReportApi, type ReportDetail, type ReportSummary } from '@/api/data';
import { errorMessage } from '@/api/client';
import { formatNumber } from './format';

/**
 * 报表中心（FR-26-007 / FR-26-009）。
 *
 * 12 类报表的内容结构不统一（有的是 core、有的是 sections），所以这里按
 * 结构分派渲染，而不是给每类写一个页面：新增报表类型时前端不用改。
 */

const CONTENT_LABEL: Record<string, string> = {
  range: '统计区间',
  generatedAt: '生成时间',
  source: '数据来源',
  definition: '口径',
  note: '说明',
  dau: 'DAU',
  dauPlay: 'DAU-Play',
  playCnt: '播放次数',
  validPlayCnt: '有效播放',
  newUsers: '新增用户',
  retentionD1: '次日留存',
  label: '指标',
  value: '数值',
  unit: '单位',
  songId: '歌曲 ID',
  name: '名称',
  playCnt2: '播放',
  roomId: '房间 ID',
  messageCnt: '消息数',
  keyword: '关键词',
  count: '次数',
  channel: '渠道',
  newUsers2: '新增用户',
  metric: '指标',
  message2: '说明',
  severity: '级别',
};

export function ReportPanel(): JSX.Element {
  const queryClient = useQueryClient();
  const [type, setType] = useState('DAILY');
  const [date, setDate] = useState<Dayjs>(dayjs().subtract(1, 'day'));

  const types = useQuery<Record<string, string>>({
    queryKey: ['admin-report-types'],
    queryFn: dataReportApi.types,
  });

  const dateText = date.format('YYYY-MM-DD');

  const detail = useQuery<ReportDetail>({
    queryKey: ['admin-report-detail', type, dateText],
    queryFn: () => dataReportApi.detail(type, dateText),
    enabled: Boolean(type),
  });

  const list = useQuery<ReportSummary[]>({
    queryKey: ['admin-report-list', type],
    queryFn: () => dataReportApi.list({ type }),
  });

  const generate = useMutation({
    mutationFn: () => dataReportApi.generate(type, dateText),
    onSuccess: () => {
      message.success('报表已重新生成');
      void queryClient.invalidateQueries({ queryKey: ['admin-report-detail'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-report-list'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const dispatch = useMutation({
    mutationFn: () => dataReportApi.dispatch(type, dateText),
    onSuccess: (result) => {
      const delivered = Number(result.delivered ?? 0);
      message.success(`已分发 ${delivered} 份（本地为站内信模拟）`);
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 220 }}
            value={type}
            onChange={setType}
            options={Object.entries(types.data ?? {}).map(([value, label]) => ({ value, label }))}
            showSearch
            optionFilterProp="label"
          />
          <DatePicker value={date} onChange={(value) => value && setDate(value)} allowClear={false} />
          <Button loading={generate.isPending} onClick={() => generate.mutate()}>
            重新生成
          </Button>
          <Button loading={dispatch.isPending} onClick={() => dispatch.mutate()}>
            分发
          </Button>
          <Button
            href={dataReportApi.exportUrl(type, dateText)}
            target="_blank"
            rel="noreferrer"
          >
            导出 CSV
          </Button>
        </Space>
      </Card>

      {detail.isError ? (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message="报表加载失败（该类型可能尚未生成）" />
      ) : null}

      <Card
        size="small"
        title={detail.data?.title ?? '报表详情'}
        extra={detail.data ? <Tag>{detail.data.reportDate}</Tag> : null}
        loading={detail.isLoading}
      >
        {detail.data ? <ReportContent detail={detail.data} /> : <Empty description="暂无内容" />}
      </Card>

      <Card size="small" title="最近生成的报表" style={{ marginTop: 16 }}>
        <Table
          rowKey="id"
          size="small"
          loading={list.isLoading}
          pagination={{ pageSize: 8, size: 'small' }}
          dataSource={list.data ?? []}
          columns={[
            { title: '类型', dataIndex: 'reportTypeLabel', width: 140 },
            { title: '日期', dataIndex: 'reportDate', width: 110 },
            { title: '标题', dataIndex: 'title' },
            { title: '状态', dataIndex: 'status', width: 80, render: (status: number) => (status === 1 ? '已生成' : '草稿') },
            { title: '分发', dataIndex: 'sentCount', width: 80, align: 'right' },
            { title: '生成时间', dataIndex: 'generatedAt', width: 170 },
          ]}
        />
      </Card>
    </div>
  );
}

function ReportContent({ detail }: { detail: ReportDetail }): JSX.Element {
  const content = detail.content ?? {};
  const sections = Array.isArray(content.sections) ? (content.sections as Array<Record<string, unknown>>) : [];
  const core = content.core as Record<string, unknown> | undefined;
  const trend = Array.isArray(content.trend) ? (content.trend as Array<Record<string, unknown>>) : [];
  const anomalies = Array.isArray(content.anomalies) ? (content.anomalies as Array<Record<string, unknown>>) : [];
  const top = content.top as Record<string, unknown> | undefined;

  const scalar = Object.entries(content).filter(
    ([key, value]) =>
      !['sections', 'core', 'trend', 'anomalies', 'top'].includes(key) &&
      (value == null || typeof value !== 'object'),
  );

  return (
    <div>
      {scalar.length > 0 ? (
        <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
          {scalar.map(([key, value]) => (
            <Descriptions.Item key={key} label={CONTENT_LABEL[key] ?? key}>
              {String(value ?? '—')}
            </Descriptions.Item>
          ))}
        </Descriptions>
      ) : null}

      {core ? (
        <Card size="small" type="inner" title="核心指标" style={{ marginBottom: 12 }}>
          <Descriptions size="small" column={3}>
            {Object.entries(core).map(([key, value]) => (
              <Descriptions.Item key={key} label={CONTENT_LABEL[key] ?? key}>
                {typeof value === 'number' ? formatNumber(value) : String(value ?? '—')}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </Card>
      ) : null}

      {sections.length === 0 && !core && anomalies.length === 0 && trend.length === 0 && !top ? (
        <Alert
          type="info"
          showIcon
          message="该报表尚未生成内容"
          description="点击上方「重新生成」按所选日期产出。"
          style={{ marginBottom: 12 }}
        />
      ) : null}

      {sections.map((section, index) => (
        <Card
          key={`${String(section.title)}-${index}`}
          size="small"
          type="inner"
          title={String(section.title ?? '分组')}
          style={{ marginBottom: 12 }}
        >
          {renderSectionItems(section.items)}
        </Card>
      ))}

      {anomalies.length > 0 ? (
        <Card size="small" type="inner" title="异常提示" style={{ marginBottom: 12 }}>
          {anomalies.map((item, index) => (
            <div key={index} style={{ marginBottom: 4 }}>
              <Tag color={item.severity === 'warning' ? 'orange' : 'blue'}>{String(item.severity ?? 'info')}</Tag>
              {String(item.message ?? item.metric ?? '')}
            </div>
          ))}
        </Card>
      ) : null}

      {top ? <TopContent top={top} /> : null}

      {trend.length > 0 ? (
        <Card size="small" type="inner" title="趋势">
          <Table
            rowKey={(row) => String(row.date ?? Math.random())}
            size="small"
            pagination={false}
            dataSource={trend}
            columns={Object.keys(trend[0] ?? {}).map((key) => ({
              title: CONTENT_LABEL[key] ?? key,
              dataIndex: key,
              align: 'right' as const,
              render: (value: unknown) => (typeof value === 'number' ? formatNumber(value) : String(value ?? '—')),
            }))}
          />
        </Card>
      ) : null}

      <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
        报表与看板同源（ADS/ODS），口径变更需走指标审批。
      </Typography.Text>
    </div>
  );
}

/** 分组项可能是「指标卡」（label/value/unit），也可能是榜单行（name/playCnt…） */
function renderSectionItems(items: unknown): JSX.Element {
  if (!Array.isArray(items) || items.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  const rows = items as Array<Record<string, unknown>>;
  const isMetric = rows.every((row) => 'label' in row && 'value' in row);
  if (isMetric) {
    return (
      <Descriptions size="small" column={3}>
        {rows.map((row, index) => (
          <Descriptions.Item key={index} label={String(row.label)}>
            {typeof row.value === 'number' ? formatNumber(row.value) : String(row.value ?? '—')}
            {row.unit ? ` ${String(row.unit)}` : ''}
          </Descriptions.Item>
        ))}
      </Descriptions>
    );
  }
  const columns = Object.keys(rows[0]).map((key) => ({
    title: CONTENT_LABEL[key] ?? key,
    dataIndex: key,
    align: typeof rows[0][key] === 'number' ? ('right' as const) : undefined,
    render: (value: unknown) => (typeof value === 'number' ? formatNumber(value) : String(value ?? '—')),
  }));
  return (
    <Table
      rowKey={(_row, index) => String(index)}
      size="small"
      pagination={false}
      dataSource={rows}
      columns={columns}
    />
  );
}

function TopContent({ top }: { top: Record<string, unknown> }): JSX.Element {
  const groups: Array<[string, string]> = [
    ['songs', '热门歌曲'],
    ['rooms', '活跃房间'],
    ['keywords', '搜索热词'],
  ];
  return (
    <>
      {groups.map(([key, title]) => {
        const items = Array.isArray(top[key]) ? (top[key] as Array<Record<string, unknown>>) : [];
        if (items.length === 0) return null;
        return (
          <Card key={key} size="small" type="inner" title={title} style={{ marginBottom: 12 }}>
            <Table
              rowKey={(_row, index) => `${key}-${index}`}
              size="small"
              pagination={false}
              dataSource={items}
              columns={Object.keys(items[0]).map((column) => ({
                title: CONTENT_LABEL[column] ?? column,
                dataIndex: column,
                align: typeof items[0][column] === 'number' ? ('right' as const) : undefined,
                render: (value: unknown) =>
                  typeof value === 'number' ? formatNumber(value) : String(value ?? '—'),
              }))}
            />
          </Card>
        );
      })}
    </>
  );
}
