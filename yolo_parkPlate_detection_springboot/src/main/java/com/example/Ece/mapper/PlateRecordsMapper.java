package com.example.Ece.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.Ece.entity.PlateRecords;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface PlateRecordsMapper extends BaseMapper<PlateRecords> {
    @Select("SELECT SUBSTRING(plate_number, 1, 1) AS province, COUNT(*) AS count " +
            "FROM platerecords " +
            "GROUP BY SUBSTRING(plate_number, 1, 1) " +
            "ORDER BY count DESC " +
            "LIMIT 5")
    List<ProvinceData> getTop();


    @Select("SELECT DATE_FORMAT(start_time, '%m-%d') AS date, COUNT(*) AS count " +
            "FROM platerecords " +
            "WHERE start_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY date")
    List<Map<String, Object>> countEntriesByDate(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT DATE_FORMAT(end_time, '%m-%d') AS date, COUNT(*) AS count " +
            "FROM platerecords " +
            "WHERE end_time IS NOT NULL " +
            "AND end_time BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY date")
    List<Map<String, Object>> countExitsByDate(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);


    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProvinceData {
        private String province;
        private Integer count;
    }

}
