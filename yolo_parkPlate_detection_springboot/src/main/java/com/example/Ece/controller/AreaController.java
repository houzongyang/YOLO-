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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/area")
public class AreaController {

    @Resource
    private AreaMapper areaMapper;
    @Resource
    private PlateRecordsMapper plateRecordsMapper;

    /**
     * 分页查询区域信息
     */
    @GetMapping
    public Result<?> findPage(@RequestParam(defaultValue = "1") Integer pageNum,
                              @RequestParam(defaultValue = "10") Integer pageSize,
                              @RequestParam(defaultValue = "") String parkArea,
                              @RequestParam(required = false, defaultValue = "-1") Integer areaStatus) {
        LambdaQueryWrapper<Area> wrapper = Wrappers.<Area>lambdaQuery();
        if (StrUtil.isNotBlank(parkArea)) {
            wrapper.like(Area::getParkArea, parkArea.trim());
        }
        if (areaStatus != null && areaStatus != -1) {
            wrapper.eq(Area::getAreaStatus, areaStatus);
        }
        wrapper.orderByDesc(Area::getId);

        Page<Area> areaPage = areaMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(areaPage);
    }

    /**
     * 车位统计概览
     */
    @GetMapping("/summary")
    public Result<?> summary() {
        List<Area> areas = areaMapper.selectList(Wrappers.<Area>lambdaQuery());
        int totalAreas = areas.size();
        int totalSpaces = 0;
        int usedSpaces = 0;
        int unusedSpaces = 0;
        int fullAreas = 0;

        for (Area area : areas) {
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            usedSpaces += used;
            unusedSpaces += unused;
            totalSpaces += used + unused;
            if (unused == 0 && used + unused > 0) {
                fullAreas++;
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("totalAreas", totalAreas);
        data.put("totalSpaces", totalSpaces);
        data.put("usedSpaces", usedSpaces);
        data.put("unusedSpaces", unusedSpaces);
        data.put("fullAreas", fullAreas);
        data.put("availableAreas", totalAreas - fullAreas);
        data.put("occupancyRate", totalSpaces == 0 ? 0 : Math.round(usedSpaces * 10000.0 / totalSpaces) / 100.0);
        return Result.success(data);
    }

    @PostMapping
    public Result<?> save(@RequestBody Area area) {
        Result<?> validateResult = validateAndNormalize(area, false);
        if (validateResult != null) {
            return validateResult;
        }

        LambdaQueryWrapper<Area> wrapper = Wrappers.<Area>lambdaQuery()
                .eq(Area::getParkArea, area.getParkArea());
        Area existingArea = areaMapper.selectOne(wrapper);
        if (existingArea != null) {
            return Result.error("-1", "名称已存在");
        }

        areaMapper.insert(area);
        return Result.success();
    }

    @PostMapping("/update")
    public Result<?> update(@RequestBody Area area) {
        Result<?> validateResult = validateAndNormalize(area, true);
        if (validateResult != null) {
            return validateResult;
        }

        LambdaQueryWrapper<Area> wrapper = Wrappers.<Area>lambdaQuery()
                .eq(Area::getParkArea, area.getParkArea())
                .ne(Area::getId, area.getId());

        if (areaMapper.selectCount(wrapper) > 0) {
            return Result.error("-1", "名称已存在");
        }

        Area existingArea = areaMapper.selectById(area.getId());
        if (existingArea == null) {
            return Result.error("-1", "区域不存在");
        }

        areaMapper.updateById(area);

        if (!area.getParkArea().equals(existingArea.getParkArea())) {
            LambdaQueryWrapper<PlateRecords> plateWrapper = Wrappers.<PlateRecords>lambdaQuery()
                    .eq(PlateRecords::getParkArea, existingArea.getParkArea())
                    .isNull(PlateRecords::getEndTime);

            List<PlateRecords> records = plateRecordsMapper.selectList(plateWrapper);
            if (!records.isEmpty()) {
                records.forEach(record -> {
                    record.setParkArea(area.getParkArea());
                    plateRecordsMapper.updateById(record);
                });
            }
        }
        return Result.success();
    }

    /**
     * 删除区域信息
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Integer id) {
        Area existingArea = areaMapper.selectById(id);
        if (existingArea == null) {
            return Result.error("-1", "区域不存在");
        }

        Integer activeRecords = plateRecordsMapper.selectCount(Wrappers.<PlateRecords>lambdaQuery()
                .eq(PlateRecords::getParkArea, existingArea.getParkArea())
                .isNull(PlateRecords::getEndTime));
        if (activeRecords > 0 || safeInteger(existingArea.getUsed()) > 0) {
            return Result.error("-1", "该区域仍有车辆停放，不能删除");
        }

        areaMapper.deleteById(id);
        return Result.success();
    }

    private Result<?> validateAndNormalize(Area area, boolean requireId) {
        if (area == null) {
            return Result.error("-1", "区域信息不能为空");
        }
        if (requireId && area.getId() == null) {
            return Result.error("-1", "区域ID不能为空");
        }
        if (StrUtil.isBlank(area.getParkArea())) {
            return Result.error("-1", "区域名称不能为空");
        }

        area.setParkArea(area.getParkArea().trim());
        area.setUsed(safeInteger(area.getUsed()));
        area.setUnused(safeInteger(area.getUnused()));
        if (area.getUsed() < 0 || area.getUnused() < 0) {
            return Result.error("-1", "车位数量不能小于0");
        }
        if (area.getPrice() == null || area.getPrice() < 0) {
            return Result.error("-1", "单价不能小于0");
        }

        area.setAreaStatus(area.getUnused() == 0 ? 1 : 0);
        return null;
    }

    private int safeInteger(Integer value) {
        return value == null ? 0 : value;
    }
}
