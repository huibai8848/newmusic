import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import {
  qualityApi,
  type DataLineage,
  type QualityReport,
  type ReconciliationItem,
} from '@/api/data';
import { errorMessage } from '@/api/client';
import { formatNumber, formatPercent } from './format';

/**
 * 数据质量 / 对账 / 血缘 / 数仓加工（FR-26-011 ~ 013）。
 *
 * 所有「跑一次」的操作都是幂等的：质量校验按日期覆盖，ETL 按日期重算。
 * 验收时可以直接连点两次，结果不会翻倍。
 */
export function QualityPanel(): JSX.Element {
  const queryClient = useQueryClient();
  const [date, setDate] = useState<Dayjs>(dayjs().subtract(1, 'day'));
  const [etlStart, setEtlStart] = useState<Dayjs>(dayjs().subtract(2, 'day'));
  const dateText = date.format('YYYY-MM-DD');

  const quality = useQuery<QualityReport>({
    queryKey: ['admin-quality', dateText],
    queryFn: () => qualityApi.report(dateText),
  });

  const reconciliation = useQuery<ReconciliationItem[]>({
    queryKey: ['admin-reconciliation', dateText],
    queryFn: () => qualityApi.reconciliation(dateText),
  });

  const lineage = useQuery<DataLineage[]>({
    queryKey: ['admin-lineage'],
    queryFn: () => qualityApi.lineage(),
  });

  const invalidate = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-quality'] });
    void queryClient.invalidateQueries({ queryKey: ['admin-reconciliation'] });
    void queryClient.invalidateQueries({ queryKey: ['admin-data-realtime'] });
  };

  const runEtl = useMutation({
    mutationFn: () => qualityApi.etl(dateText),
    onSuccess: (result) => {
      message.success(`数仓加工完成：${String(result.date ?? dateText)}`);
      invalidate();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const backfill = useMutation({
    mutationFn: () => qualityApi.backfill(etlStart.format('YYYY-MM-DD'), dateText),
    onSuccess: (result) => {
      message.success(`回溯完成：${String(result.start ?? '')} ~ ${String(result.end ?? '')}`);
      invalidate();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const runQuality = useMutation({
    mutationFn: () => qualityApi.run(dateText),
    onSuccess: (result) => {
      message.success(`质量校验完成，得分 ${String(result.score ?? '—')}`);
      invalidate();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const runReconciliation = useMutation({
    mutationFn: () => qualityApi.runReconciliation(dateText),
    onSuccess: (result) => {
      message.success(`对账完成：通过 ${result.passed}/${result.total}`);
      invalidate();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <DatePicker value={date} onChange={(value) => value && setDate(value)} allowClear={false} />
          <Button type="primary" loading={runEtl.isPending} onClick={() => runEtl.mutate()}>
            加工数仓
          </Button>
          <DatePicker
            value={etlStart}
            onChange={(value) => value && setEtlStart(value)}
            allowClear={false}
            placeholder="回溯开始"
          />
          <Button loading={backfill.isPending} onClick={() => backfill.mutate()}>
            回溯重算
          </Button>
          <Button loading={runQuality.isPending} onClick={() => runQuality.mutate()}>
            质量校验
          </Button>
          <Button loading={runReconciliation.isPending} onClick={() => runReconciliation.mutate()}>
            执行对账
          </Button>
        </Space>
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={8}>
          <Card size="small" title={`质量得分 · ${quality.data?.checkDate ?? dateText}`}>
            <Statistic
              value={quality.data?.score ?? 0}
              suffix="分"
              valueStyle={{
                color:
                  (quality.data?.score ?? 100) >= 90
                    ? '#3f8600'
                    : (quality.data?.score ?? 100) >= 70
                      ? '#d48806'
                      : '#cf1322',
              }}
            />
            <Descriptions size="small" column={1} style={{ marginTop: 12 }}>
              <Descriptions.Item label="健康度">
                <Tag color={quality.data?.health === 'healthy' ? 'green' : quality.data?.health === 'warning' ? 'orange' : 'red'}>
                  {quality.data?.health ?? 'unknown'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="规则通过">
                {formatNumber(quality.data?.passed)} / {formatNumber(quality.data?.total)}
              </Descriptions.Item>
              <Descriptions.Item label="阻断写入">
                {quality.data?.blocked ? <Tag color="red">是</Tag> : '否'}
                {quality.data?.criticalFailed ? ` · 严重失败 ${quality.data.criticalFailed}` : ''}
              </Descriptions.Item>
              <Descriptions.Item label="耗时">{formatNumber(quality.data?.costMs)} ms</Descriptions.Item>
            </Descriptions>
            {quality.data && quality.data.total === 0 ? (
              <Alert type="info" showIcon message="当日尚未跑过质量校验" description="点击上方「质量校验」生成，或先加工数仓。" />
            ) : null}
          </Card>
        </Col>
        <Col xs={24} xl={16}>
          <Card size="small" title="质量问题">
            <Table
              rowKey={(_row, index) => String(index)}
              size="small"
              loading={quality.isLoading}
              pagination={false}
              dataSource={quality.data?.issues ?? []}
              columns={[
                { title: '表', dataIndex: 'table', width: 200 },
                { title: '规则', dataIndex: 'ruleType', width: 120 },
                { title: '表达式', dataIndex: 'ruleExpr', ellipsis: true },
                { title: '实际值', dataIndex: 'actualValue', width: 120, align: 'right' },
                {
                  title: '级别',
                  dataIndex: 'severity',
                  width: 90,
                  render: (severity: string) => (
                    <Tag color={severity === 'critical' ? 'red' : severity === 'warning' ? 'orange' : 'default'}>
                      {severity}
                    </Tag>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title={`对账结果 · ${dateText}`}
        style={{ marginTop: 16 }}
        extra={
          reconciliation.data
            ? `${formatPercent(
                reconciliation.data.filter((row) => row.passed).length / Math.max(1, reconciliation.data.length) * 100,
                0,
              )} 通过`
            : null
        }
      >
        <Table
          rowKey={(row) => `${row.checkDate ?? dateText}-${row.item}`}
          size="small"
          loading={reconciliation.isLoading}
          pagination={false}
          dataSource={reconciliation.data ?? []}
          columns={[
            { title: '对账项', dataIndex: 'item', width: 180 },
            { title: '数仓值', dataIndex: 'dwValue', align: 'right' },
            { title: '业务值', dataIndex: 'bizValue', align: 'right' },
            { title: '偏差', dataIndex: 'deviation', align: 'right', render: (value: number) => `${Number(value).toFixed(2)}%` },
            { title: '阈值', dataIndex: 'threshold', align: 'right', render: (value: number) => `${Number(value).toFixed(2)}%` },
            {
              title: '结果',
              dataIndex: 'passed',
              width: 90,
              render: (passed: boolean) => (passed ? <Tag color="green">通过</Tag> : <Tag color="red">偏差超限</Tag>),
            },
          ]}
        />
      </Card>

      <Card size="small" title="数据血缘" style={{ marginTop: 16 }}>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          ODS → DWD → DWS → ADS 的字段级来源；排查「指标变了」时先看这里对应到哪张源表。
        </Typography.Text>
        <Table
          rowKey="id"
          size="small"
          loading={lineage.isLoading}
          pagination={{ pageSize: 10, size: 'small' }}
          dataSource={lineage.data ?? []}
          columns={[
            { title: '目标表', dataIndex: 'tableName', width: 180 },
            { title: '目标字段', dataIndex: 'columnName', width: 160 },
            { title: '上游表', dataIndex: 'upstreamTable', width: 180 },
            { title: '上游字段', dataIndex: 'upstreamColumn', width: 160 },
            { title: '转换', dataIndex: 'transform', ellipsis: true },
          ]}
        />
      </Card>
    </div>
  );
}
