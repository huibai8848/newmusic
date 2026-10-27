package com.example.newmusic.module.search.service;

import com.example.newmusic.module.search.dto.SearchQueryReq;
import com.example.newmusic.module.search.vo.SearchResultVO;

/**
 * 搜索主流程（FR-15-001/002/006，§5.2、§6.1）。
 *
 * <p>这个接口是「引擎之上」的全部逻辑：参数校验、敏感词、降级路由、配额拆分、
 * 高亮组装、异步记账。它**不认识任何具体引擎**——只通过
 * {@link com.example.newmusic.module.search.engine.SearchEngine} 端口取数据，
 * 所以装 ES 时这一层一行都不用改（D-33 / 附 A）。
 */
public interface SearchService {

    SearchResultVO search(SearchQueryReq request);
}
