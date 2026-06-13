package com.example.Ece.controller;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.Ece.common.Result;
import com.example.Ece.entity.Area;
import com.example.Ece.entity.PlateRecords;
import com.example.Ece.mapper.AreaMapper;
import com.example.Ece.mapper.PlateRecordsMapper;
import com.example.Ece.service.SmartParkingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/plate")
public class PlateRecordsController {

    @Resource
    private PlateRecordsMapper plateRecordsMapper;
    @Resource
    private AreaMapper areaMapper;
    @Resource
    private AreaController areaController;
    @Resource
    private SmartParkingService smartParkingService;
    /**
     * 分页查询区域信息
     */
    @GetMapping
    public Result<?> findPage(@RequestParam(defaultValue = "1") Integer pageNum,
                              @RequestParam(defaultValue = "10") Integer pageSize,
                              @RequestParam(defaultValue = "") String parkArea,
                              @RequestParam(defaultValue = "") String plateNumber,
                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                              @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        LambdaQueryWrapper<PlateRecords> wrapper = Wrappers.<PlateRecords>lambdaQuery();
        wrapper.orderByDesc(PlateRecords::getStartTime);
        if (StrUtil.isNotBlank(parkArea)) {
            wrapper.like(PlateRecords::getParkArea, parkArea);
        }
        if (StrUtil.isNotBlank(plateNumber)) {
            wrapper.like(PlateRecords::getPlateNumber, plateNumber);
        }
// 时间范围筛选 (基于 startTime 和 endTime)
        if (startTime != null && endTime != null) {
            // 入场时间在指定范围内
            wrapper.between(PlateRecords::getStartTime, startTime, endTime);

            // 或者：查找在时间段内停留过的车辆
            // wrapper.le(PlateRecords::getStartTime, endTime)
            //     .ge(PlateRecords::getEndTime, startTime);

        } else if (startTime != null) {
            // 入场时间晚于指定时间
            wrapper.ge(PlateRecords::getStartTime, startTime);

            // 或者：离开时间晚于指定时间
            // wrapper.ge(PlateRecords::getEndTime, startTime);

        } else if (endTime != null) {
            // 入场时间早于指定时间
            wrapper.le(PlateRecords::getStartTime, endTime);

            // 或者：离开时间早于指定时间
            // wrapper.le(PlateRecords::getEndTime, endTime);
        }
        Page<PlateRecords> areaPage = plateRecordsMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(areaPage);
    }

    @PostMapping
    public Result<?> save(@RequestBody PlateRecords plateRecords) {

        PlateRecords existingPlateNUmber = plateRecordsMapper.selectOne(
                new LambdaQueryWrapper<PlateRecords>()
                        .eq(PlateRecords::getPlateNumber, plateRecords.getPlateNumber())
                        .isNull(PlateRecords::getEndTime) // 关键修改：只查询未结束的记录
        );

        if (existingPlateNUmber != null) {
            // 存在未结束的记录 → 执行更新（结束停车）
            LocalDateTime currentDateTime = LocalDateTime.now();
            existingPlateNUmber.setEndTime(currentDateTime);

            // 计算时长（小时）
            Duration tempduration = Duration.between(existingPlateNUmber.getStartTime(), currentDateTime);
            double hours = tempduration.toMinutes() / 60.0;
            existingPlateNUmber.setDuration(hours);

            // 计算动态费用：免费时长、高峰系数、占用率系数、封顶价
            Area existingArea = areaMapper.selectOne(new LambdaQueryWrapper<Area>()
                    .eq(Area::getParkArea, existingPlateNUmber.getParkArea()));
            if (existingArea == null) {
                return Result.error("-1", "停车区域不存在，无法结算");
            }
            existingPlateNUmber.setPrice(smartParkingService.calculateDynamicFee(existingArea, hours, currentDateTime));

            // 更新停车记录
            update(existingPlateNUmber);

            // 释放车位
            existingArea.setUnused(existingArea.getUnused() + 1);
            existingArea.setUsed(existingArea.getUsed() - 1);
            existingArea.setAreaStatus(existingArea.getUnused() > 0 ? 0 : 1); // 动态计算状态
            areaController.update(existingArea);

            return Result.success();
        } else {
            // 不存在未结束的记录 → 执行插入（开始新停车）
            Map<String, Object> recommendation = smartParkingService.recommend(plateRecords.getPlateNumber());
            if (!Boolean.TRUE.equals(recommendation.get("available"))) {
                return Result.error("1", "无可用停车区域");
            }

            List<String> areaNames = new ArrayList<>();
            areaNames.add((String) recommendation.get("parkArea"));
            List<?> alternatives = (List<?>) recommendation.get("alternatives");
            if (alternatives != null) {
                for (Object alternative : alternatives) {
                    if (alternative instanceof Map) {
                        Object areaName = ((Map<?, ?>) alternative).get("parkArea");
                        if (areaName != null && !areaNames.contains(areaName.toString())) {
                            areaNames.add(areaName.toString());
                        }
                    }
                }
            }

            for (String areaName : areaNames) {
                Area area = areaMapper.selectOne(new LambdaQueryWrapper<Area>()
                        .eq(Area::getParkArea, areaName));

                if (area != null && area.getUnused() > 0) {
                    // 占用车位
                    area.setUnused(area.getUnused() - 1);
                    area.setUsed(area.getUsed() + 1);
                    area.setAreaStatus(area.getUnused() == 0 ? 1 : 0); // 动态计算状态
                    areaController.update(area);

                    // 创建新记录
                    plateRecords.setParkArea(areaName);
                    plateRecords.setStartTime(LocalDateTime.now());
                    plateRecords.setEndTime(null); // 明确设置为未结束
                    plateRecordsMapper.insert(plateRecords);

                    return Result.success();
                }
            }
            return Result.error("1", "车位已满");
        }
    }


    @PostMapping("/update")
    public Result<?> update(@RequestBody PlateRecords plateRecords) {
//        LambdaQueryWrapper<PlateRecords> wrapper = Wrappers.<PlateRecords>lambdaQuery()
//                .eq(PlateRecords::getPlateNumber, plateRecords.getPlateNumber())
//                .ne(PlateRecords::getId, plateRecords.getId());
//        PlateRecords existingArea = plateRecordsMapper.selectOne(wrapper);
//        if (existingArea != null) {
//            return Result.error("-1", "温室名称已存在");
//        }

        plateRecordsMapper.updateById(plateRecords);
        return Result.success();
    }

    /**
     * 删除信息
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Integer id) {
        plateRecordsMapper.deleteById(id);
        return Result.success();
    }

    @GetMapping("/top")
    public Result<?> getTop(){

        List<PlateRecordsMapper.ProvinceData> topProvinces = plateRecordsMapper.getTop();

        return Result.success(topProvinces);
    }



    @GetMapping("/recent")
    public Result<Map<String, Object>> getRecentInOutData(@RequestParam(name = "days", defaultValue = "10") int days) {
        // 验证天数参数
        if (days < 1 || days > 90) {
            return Result.error("1", "天数参数应在1-90之间");
        }

        try {
            // 确定日期范围
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days - 1);
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

            // 查询入场统计
            List<Map<String, Object>> entryCounts = plateRecordsMapper.countEntriesByDate(
                    startDateTime, endDateTime
            );

            // 查询出场统计
            List<Map<String, Object>> exitCounts = plateRecordsMapper.countExitsByDate(
                    startDateTime, endDateTime
            );

            // 生成连续日期列表
            List<String> dates = new ArrayList<>();
            List<Integer> entryData = new ArrayList<>();
            List<Integer> exitData = new ArrayList<>();

            LocalDate current = startDate;
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");

            while (!current.isAfter(endDate)) {
                String dateStr = current.format(formatter);
                dates.add(dateStr);

                // 填充入场数据
                entryData.add(getCountForDate(entryCounts, dateStr));

                // 填充出场数据
                exitData.add(getCountForDate(exitCounts, dateStr));

                current = current.plusDays(1);
            }

            // 构建返回的data
            Map<String, Object> data = new HashMap<>();
            data.put("dates", dates);
            data.put("entryData", entryData);
            data.put("exitData", exitData);

            return Result.success(data);
        } catch (Exception e) {
            return Result.error("-1", "服务器内部错误: " + e.getMessage());
        }
    }

    private int getCountForDate(List<Map<String, Object>> counts, String date) {
        for (Map<String, Object> count : counts) {
            if (date.equals(count.get("date"))) {
                return ((Number) count.get("count")).intValue();
            }
        }
        return 0;
    }




}
