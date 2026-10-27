package com.example.newmusic.module.dict.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.dict.entity.SynonymWord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SynonymMapper extends BaseMapper<SynonymWord> {
}
