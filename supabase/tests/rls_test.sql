\set A 'aaaaaaaa-0000-0000-0000-000000000001'
\set B 'bbbbbbbb-0000-0000-0000-000000000002'
\set C 'cccccccc-0000-0000-0000-000000000003'
\set DA 'a1000000-0000-0000-0000-000000000001'
\set DB 'b2000000-0000-0000-0000-000000000002'
-- server (service_role) creates an approved request + active session
set role service_role;
insert into pairing_requests(id,owner_id,requester_owner_id,target_owner_id,requester_device_id,target_device_id,code_hash,status)
 values ('d0000000-0000-0000-0000-000000000002',null,:'A',:'B',:'DA',:'DB','h2','approved');
insert into remote_sessions(id,controller_owner_id,agent_owner_id,controller_device_id,agent_device_id,status,pairing_request_id)
 values ('e0000000-0000-0000-0000-000000000001',:'A',:'B',:'DA',:'DB','active','d0000000-0000-0000-0000-000000000002');
insert into signaling_messages(session_id,sender_device_id,recipient_device_id,message_type,payload)
 values ('e0000000-0000-0000-0000-000000000001',:'DA',:'DB','offer','{"sdp":"x"}'),
        ('e0000000-0000-0000-0000-000000000001',:'DB',:'DA','answer','{"sdp":"y"}');
reset role;

create or replace function t(name text, ok boolean) returns void language plpgsql as $$ begin raise notice '% : %', case when ok then 'PASS' else 'FAIL' end, name; end $$;
grant execute on function t(text,boolean) to authenticated;

create or replace function must_fail(name text, stmt text) returns void language plpgsql as $$
declare n int; begin
  begin execute stmt; get diagnostics n = row_count;
    perform t(name || ' (affected '||n||' rows)', n = 0);
  exception when others then perform t(name || ' [' || sqlerrm || ']', true); end;
end $$;
grant execute on function must_fail(text,text) to authenticated;
create or replace function must_fail2(name text, stmt text) returns void language plpgsql as $$ begin
  begin execute stmt; perform t(name, false); exception when unique_violation then perform t(name, true); end; end $$;
grant execute on function must_fail2(text,text) to service_role, authenticated;
\echo == visibility
set role authenticated; select set_config('request.jwt.claim.sub', :'A', false);
select t('A sees the session', (select count(*) from remote_sessions)=1);
select t('A sees both pairing requests (backfilled legacy + new)', (select count(*) from pairing_requests)=2);
select t('A sees only signaling addressed to own device (answer)', (select string_agg(message_type,',') from signaling_messages)='answer');
select set_config('request.jwt.claim.sub', :'B', false);
select t('B sees the session', (select count(*) from remote_sessions)=1);
select t('B sees only signaling addressed to own device (offer)', (select string_agg(message_type,',') from signaling_messages)='offer');
select t('B sees the pairing request targeting it', (select count(*) from pairing_requests)=2);
select set_config('request.jwt.claim.sub', :'C', false);
select t('C (outsider) sees no sessions', (select count(*) from remote_sessions)=0);
select t('C sees no pairing requests', (select count(*) from pairing_requests)=0);
select t('C reads no signaling', (select count(*) from signaling_messages)=0);
select t('C sees no devices', (select count(*) from devices)=0);

\echo == client writes must fail
select set_config('request.jwt.claim.sub', :'B', false);
select must_fail('B cannot update pairing status', $$update pairing_requests set status='approved'$$);
select must_fail('B cannot insert a pairing request', $$insert into pairing_requests(requester_owner_id,target_owner_id,requester_device_id,target_device_id,code_hash) values ('bbbbbbbb-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001','x')$$);
select must_fail('B cannot update session status', $$update remote_sessions set status='active'$$);
select must_fail('B cannot insert a session', $$insert into remote_sessions(controller_owner_id,agent_owner_id,controller_device_id,agent_device_id) values ('bbbbbbbb-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001')$$);
select must_fail('B cannot insert signaling directly', $$insert into signaling_messages(session_id,sender_device_id,recipient_device_id,message_type,payload) values ('e0000000-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001','offer','{}')$$);
select must_fail('B cannot forge audit logs', $$insert into audit_logs(owner_id,event_type) values ('bbbbbbbb-0000-0000-0000-000000000002','x')$$);
select must_fail('B cannot delete sessions', $$delete from remote_sessions$$);

\echo == devices
select must_fail('B cannot take over A device (owner_id change)', $$update devices set owner_id='bbbbbbbb-0000-0000-0000-000000000002' where id='a1000000-0000-0000-0000-000000000001'$$);
select must_fail('B cannot change own owner_id', $$update devices set owner_id='aaaaaaaa-0000-0000-0000-000000000001' where id='b2000000-0000-0000-0000-000000000002'$$);
select must_fail('B cannot rename A device', $$update devices set device_name='hacked' where id='a1000000-0000-0000-0000-000000000001'$$);
select must_fail('B cannot insert a device owned by A', $$insert into devices(owner_id,device_name) values ('aaaaaaaa-0000-0000-0000-000000000001','fake')$$);
select must_fail('B cannot choose its own pairing_code', $$insert into devices(owner_id,device_name,pairing_code) values ('bbbbbbbb-0000-0000-0000-000000000002','x','AAAAAAAA')$$);
select must_fail('B cannot rewrite pairing_code', $$update devices set pairing_code='AAAAAAAA'$$);
update devices set last_seen=now(), device_name='Phone B2' where id='b2000000-0000-0000-0000-000000000002';
select t('B can update own last_seen/name', (select device_name from devices where id='b2000000-0000-0000-0000-000000000002')='Phone B2');
insert into devices(owner_id,device_name) values (:'B','Phone B second');
select t('B can register own device; pairing_code auto-generated (8 chars)', (select count(*) from devices where length(pairing_code)=8 and device_name='Phone B second')=1);
update devices set revoked_at=now() where device_name='Phone B second';
select must_fail('revoked device cannot be restored', $$update devices set revoked_at=null where device_name='Phone B second'$$);

\echo == server-side invariants (service_role)
reset role; set role service_role;
select must_fail2('replay: 2nd session from the same approved request is rejected', $$insert into remote_sessions(controller_owner_id,agent_owner_id,controller_device_id,agent_device_id,status,pairing_request_id) values ('aaaaaaaa-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','active','d0000000-0000-0000-0000-000000000002')$$);
select must_fail2('agent device cannot be in two active sessions', $$insert into remote_sessions(controller_owner_id,agent_owner_id,controller_device_id,agent_device_id,status) values ('aaaaaaaa-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','active')$$);
select must_fail2('duplicate pending request for same device pair is rejected', $$insert into pairing_requests(requester_owner_id,target_owner_id,requester_device_id,target_device_id,code_hash) values ('aaaaaaaa-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000001','b2000000-0000-0000-0000-000000000002','p2')$$);

\echo == session ends -> signaling disappears for participants
update remote_sessions set status='ended', ended_at=now() where id='e0000000-0000-0000-0000-000000000001';
reset role; set role authenticated; select set_config('request.jwt.claim.sub', :'A', false);
select t('ended session: no signaling visible', (select count(*) from signaling_messages)=0);
