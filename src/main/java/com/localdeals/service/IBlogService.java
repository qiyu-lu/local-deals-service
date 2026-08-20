package com.localdeals.service;

import com.localdeals.dto.Result;
import com.localdeals.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result setBlogLiked(Long id, boolean liked);

    Result queryBlogLikes(Long id);

    Result queryBlogsByUserId(Long userId, Integer current);

    Result queryBlogsByShopId(Long shopId, Integer current);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);

    Result searchBlogs(String keyword, Integer current);
}
