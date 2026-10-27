package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.socialupgrade.entity.Topic;
import com.example.newmusic.module.socialupgrade.service.TopicService;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Api(tags = "社交升级-话题")
@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    private final TopicService topicService;

    public TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    @ApiOperation("热门话题")
    @GetMapping("/hot")
    public R<List<Topic>> hot(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(topicService.hot(limit));
    }

    @ApiOperation("话题搜索")
    @GetMapping("/search")
    public R<List<Topic>> search(@RequestParam String keyword,
                                 @RequestParam(defaultValue = "10") int limit) {
        return R.ok(topicService.search(keyword, limit));
    }

    @ApiOperation("话题下的动态")
    @GetMapping("/{name}/posts")
    public R<PageResult<PostVO>> posts(@PathVariable String name,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(topicService.posts(UserContext.getUserId(), name, page, size));
    }
}
