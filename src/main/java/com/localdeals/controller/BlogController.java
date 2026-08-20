package com.localdeals.controller;


import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.service.IBlogService;
import com.localdeals.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/{id}/like")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.setBlogLiked(id, true);
    }

    @DeleteMapping("/{id}/like")
    public Result unlikeBlog(@PathVariable("id") Long id) {
        return blogService.setBlogLiked(id, false);
    }

    /**
     * Migration bridge for clients which already send an explicit desired state. Parameterless
     * legacy toggle requests are deliberately rejected because an HTTP retry could invert state.
     */
    @Deprecated
    @PutMapping("/like/{id}")
    public Result setBlogLikedCompatibility(@PathVariable("id") Long id,
                                            @RequestParam("liked") boolean liked) {
        return blogService.setBlogLiked(id, liked);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        return blogService.queryBlogsByUserId(user.getId(), current);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return blogService.queryBlogsByUserId(id, current);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }

    @GetMapping("/of/shop")
    public Result queryBlogByShopId(
            @RequestParam("id") Long shopId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogsByShopId(shopId, current);
    }

    /**
     * 基于 Elasticsearch 的探店笔记搜索（IK 分词）
     * @param keyword 关键词
     * @param current 页码
     * @return 笔记列表
     */
    @GetMapping("/search")
    public Result searchBlogs(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.searchBlogs(keyword, current);
    }
}
