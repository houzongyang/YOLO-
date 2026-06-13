package com.example.Ece.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.Ece.entity.Area;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AreaMapper extends BaseMapper<Area> {
    @Select("Select park_area from area")
    List<String> getArea();
}
