package com.baomidou.jobs.starter.monitor;

import com.baomidou.jobs.starter.JobsHelper;
import com.baomidou.jobs.starter.cron.CronExpression;
import com.baomidou.jobs.starter.entity.JobsInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;

/**
 * jobs 心跳
 *
 * @author jobob
 */
@Slf4j
public class JobsHeartbeat implements Runnable {

    @Override
    public void run() {
        // 扫描任务
        Connection conn = null;
        PreparedStatement preparedStatement = null;
        try {
            if (conn == null || conn.isClosed()) {
                conn = JobsHelper.getDataSource().getConnection();
            }
            conn.setAutoCommit(false);

            preparedStatement = conn.prepareStatement("SELECT * FROM jobs_lock WHERE lock_name = 'schedule_lock' FOR UPDATE");
            preparedStatement.execute();

            // tx start
            long nowTime = System.currentTimeMillis();
            // 1、预读10s内调度任务
            List<JobsInfo> scheduleList = JobsHelper.getJobInfoService().scheduleJobQuery(nowTime + 10000);
            if (scheduleList != null && scheduleList.size() > 0) {
                // 2、推送时间轮
                for (JobsInfo jobInfo : scheduleList) {
                    long waitSecond;
                    if (jobInfo.getTriggerNextTime() < nowTime - 10000) {
                        // 过期超10s：本地忽略，当前时间开始计算下次触发时间
                        waitSecond = -1;
                    } else if (jobInfo.getTriggerNextTime() < nowTime) {
                        // 过期10s内：立即触发，计算延迟触发时长
                        waitSecond = nowTime - jobInfo.getTriggerLastTime();
                    } else {
                        // 未过期：等待下次循环
                        continue;
                    }
                    jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
                    jobInfo.setTriggerNextTime(new CronExpression(jobInfo.getJobCron())
                            .getNextValidTimeAfter(new Date()).getTime());
                    if (waitSecond >= 0) {
                        System.out.println("触发执行：" + jobInfo.getJobCron() + "==waitSecond===" + waitSecond);
                        // 推送任务消息
                        JobsHelper.getJobsDisruptorTemplate().publish(jobInfo, waitSecond);
                    }
                    // 更新任务状态
                    JobsHelper.getJobInfoService().updateById(jobInfo);
                }

            }

            // tx stop
            conn.commit();
        } catch (Exception e) {
            log.error("Jobs, JobsScheduleHelper#scheduleThread error:{}", e);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                }
            }
            if (null != preparedStatement) {
                try {
                    preparedStatement.close();
                } catch (SQLException ignore) {
                }
            }
        }
        log.info("Jobs, JobsScheduleHelper#scheduleThread stop");
    }
}