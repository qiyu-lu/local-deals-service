package com.localdeals.dto;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;

@Data
@Document(indexName = "shop_index")
public class ShopDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String address;

    @Field(type = FieldType.Keyword)
    private Long typeId;

    @Field(type = FieldType.Long)
    private Long avgPrice;

    @Field(type = FieldType.Integer)
    private Integer score;

    @Field(type = FieldType.Integer)
    private Integer sold;

    @GeoPointField
    private String location;  // 格式："lat,lon"，例如 "30.33,120.15"
}
