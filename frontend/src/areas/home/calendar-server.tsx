// 홈 오른쪽의 오늘 수업. 서버 시간표의 today와 slots만 사용한다.
import { useEffect, type ReactNode } from "react";
import { useResource } from "../../shared/remote-cache.ts";
import { timetables, koShortDay, clock, calendarMessage } from "../../shared/calendar-remote.ts";
import { RetryButton } from "./home-server.tsx";

export function serverTodayClasses(): ReactNode {
  return <ServerTodayClasses />;
}

function ServerTodayClasses() {
  const entry = useResource(timetables, "", () => void timetables.load("", ""));
  useEffect(() => {
    void timetables.visit("home:today", "", "");
  });
  const data = entry && entry.data;
  const today = data ? (data.slots || []).filter((slot) => slot.date === data.today) : [];
  return (
    <>
      <div className="aside-h"><h2>오늘 수업</h2><span className="tiny">{data ? koShortDay(data.today) : ""}</span></div>
        {!data
          ? <p className="tiny" role={entry && entry.error ? "alert" : "status"}>{entry && entry.error ? <>{calendarMessage(entry.error)} <RetryButton reload={() => void timetables.load("", "")} /></> : "오늘 수업을 불러오는 중입니다."}</p>
          : today.length
            ? today.map((slot, index) => (
              <article key={index} className="aside-card today-class">
                <b>{slot.courseName}</b>
                <div className="tiny">{slot.courseCode ? `(${[slot.courseCode, slot.section].filter(Boolean).join("-")})` : ""}</div>
                <div className="time-row"><i className="dot" /><span>{slot.startTime ? [clock(slot.startTime), clock(slot.endTime)].filter(Boolean).join("~") : slot.periodStart != null ? `${slot.periodStart}${slot.periodEnd != null ? `~${slot.periodEnd}` : ""}교시` : "시간 미정"}</span></div>
                <div className="tiny">{slot.room || "-"}</div>
              </article>
            ))
            : <p className="tiny">오늘은 수업이 없습니다.</p>}
    </>
  );
}
