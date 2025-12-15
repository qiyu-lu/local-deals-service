package com.hmdp.utils;


import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ShopBloomFilter {
    private BloomFilter<Long> bloomFilter;

    @Autowired
    private IShopService shopService;

    @PostConstruct
    public void init(){
        bloomFilter = BloomFilter.create(
                Funnels.longFunnel(),
                1_000_000,
                0.01
        );

        //加载所有 shopId
        List<Long> ids = shopService.list()
                .stream()
                .map(Shop::getId)
                .collect(Collectors.toList());
        ids.forEach(bloomFilter::put);
    }

    public boolean mightContain(Long id){
        return bloomFilter.mightContain(id);
    }
    public void add(Long id){
        bloomFilter.put(id);
    }
}
