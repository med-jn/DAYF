-- شغّله مرة واحدة في Supabase → SQL Editor (بعد migration_v4.sql)

alter table agent_config add column if not exists speech_style text default 'دافئ وقريب';
alter table agent_config add column if not exists affective_dialog boolean default true;

-- استجابة أسرع: يعتبر الصمت نهاية جملة بعد 700 م.ث
update agent_config set silence_ms = 700 where id = 1 and silence_ms >= 800;
