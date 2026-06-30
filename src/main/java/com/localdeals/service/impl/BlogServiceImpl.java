package com.localdeals.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.BlogDoc;
import com.localdeals.dto.Result;
import com.localdeals.dto.ScrollResult;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.entity.Follow;
import com.localdeals.entity.User;
import com.localdeals.mapper.BlogMapper;
import com.localdeals.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.service.IFollowService;
import com.localdeals.service.IUserService;
import com.localdeals.utils.SystemConstants;
import com.localdeals.utils.UserHolder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.localdeals.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.localdeals.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FOLLOW_FEED_PAGE_SIZE = 2;

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService  followService;

    @Autowired
    private ElasticsearchRestTemplate esRestTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){return Result.fail("笔记不存在!");}
        //查询Blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        //1.获取登录用户
        Long userId = user.getId();
        //2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3.如果未点赞，可以点赞
            //3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2保存用户到redis的set集合中
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        else{
            //4.如果已经点赞，取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2把用户从redis的set集合中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5个点赞的用户， zrange key 0 4 查完后得到的用户id和分数就是之前代码中设置的时间戳
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null ||  top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.根据用户id查询用户
//        List<UserDTO> userDTOS = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());

        String idStr = StrUtil.join("," , ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id, " + idStr+ ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("新增笔记失败!");
        }
        //查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有的粉丝
        for( Follow follow : follows){
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    //查询收件箱的所有笔记实现滚动分页
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, FOLLOW_FEED_PAGE_SIZE);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3.解析数据 blogId，score(时间戳)、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            ids.add(Long.valueOf(typedTuple.getValue()));

            long time = typedTuple.getScore().longValue();
            if(time == minTime) os++;
            else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据id查询blog
        String idStr = StrUtil.join("," , ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();

        for(Blog blog : blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) { return; }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result searchBlogs(String keyword, Integer current) {
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        if (StrUtil.isNotBlank(keyword)) {
            queryBuilder.withQuery(QueryBuilders.multiMatchQuery(keyword, "title", "content"));
        } else {
            queryBuilder.withQuery(QueryBuilders.matchAllQuery());
        }
        queryBuilder.withPageable(PageRequest.of(current - 1, SystemConstants.DEFAULT_PAGE_SIZE));

        SearchHits<BlogDoc> hits = esRestTemplate.search(queryBuilder.build(), BlogDoc.class,
                IndexCoordinates.of("blog_index"));
        List<BlogDoc> docs = hits.getSearchHits().stream()
                .map(SearchHit::getContent).collect(Collectors.toList());
        return Result.ok(docs);
    }
}
