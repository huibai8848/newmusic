import { useMemo, useState } from 'react';
import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { profileApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import type { Id } from '@/types/api';
import type { UserBrief } from '@/types/social';

interface Props {
  value: UserBrief[];
  onChange: (users: UserBrief[]) => void;
  placeholder?: string;
  excludeIds?: Id[];
  maxCount?: number;
  disabled?: boolean;
}

/**
 * 用户多选（群聊创建/邀请成员共用）。
 *
 * 选中的用户必须保留在自己的 options 里：搜索词一变，后端结果会整体替换，
 * 若不合并已选项，AntD 会把已选标签退化成裸 ID。
 */
export function UserMultiSelect({
  value,
  onChange,
  placeholder = '搜索昵称添加成员',
  excludeIds = [],
  maxCount,
  disabled,
}: Props): JSX.Element {
  const [keyword, setKeyword] = useState('');
  const debounced = useDebouncedValue(keyword.trim(), 250);

  const query = useQuery({
    queryKey: socialUpgradeKeys.userSearch(`multi:${debounced}`),
    queryFn: () => profileApi.searchUsers(debounced, 10),
    enabled: debounced.length > 0,
    staleTime: 30_000,
  });

  const exclude = useMemo(() => new Set(excludeIds.map(String)), [excludeIds]);

  const options = useMemo(() => {
    const seen = new Set<string>();
    const users: UserBrief[] = [];
    for (const user of [...value, ...(query.data ?? [])]) {
      if (seen.has(String(user.id)) || exclude.has(String(user.id))) continue;
      seen.add(String(user.id));
      users.push(user);
    }
    return users.map((user) => ({
      value: user.id,
      label: user.nickname ?? '用户',
    }));
  }, [value, query.data, exclude]);

  const userMap = useMemo(() => {
    const map = new Map<string, UserBrief>();
    for (const user of value) map.set(String(user.id), user);
    for (const user of query.data ?? []) map.set(String(user.id), user);
    return map;
  }, [value, query.data]);

  return (
    <Select
      mode="multiple"
      allowClear
      showSearch
      disabled={disabled}
      value={value.map((user) => user.id)}
      options={options}
      placeholder={placeholder}
      filterOption={false}
      onSearch={setKeyword}
      onChange={(ids: Id[]) => {
        onChange(
          ids
            .map((id) => userMap.get(String(id)))
            .filter((user): user is UserBrief => Boolean(user)),
        );
      }}
      maxCount={maxCount}
      notFoundContent={
        debounced.length === 0 ? (
          '输入昵称搜索用户'
        ) : query.isLoading ? (
          <Spin size="small" />
        ) : (
          '没有找到相关用户'
        )
      }
      style={{ width: '100%' }}
    />
  );
}
