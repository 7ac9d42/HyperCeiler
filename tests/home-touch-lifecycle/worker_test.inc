unsigned failures=0;
void check(bool result,const char* name){
 std::cout<<name<<": "<<(result?"PASS":"FAIL")<<'\n';failures+=!result;
}
void touch(int action,int64_t time,float y=1000){
 Event e{action,action,1,time,500,y,7};hc_workspace_scene(reinterpret_cast<void*>(0x100),&e);
 assert(e.masked==action&&e.full==action);
}
int main(){
 reset_fixture();
 settings.mask=31;settings.quick=true;publish_workspace_locked();
 // One real queue drain followed by a fatal read, with EINTR before it.
 emit(home_gestures::Up,1);
 touch(0,1000);assert(workspace_state.authorize(1000,1));touch(2,1100,900);
 assert(!stock_active&&forwarded==std::vector<int>({0,3}));
 action_worker(nullptr);
 check(reads==3&&emitted==std::vector<int>({home_gestures::Up}),"EINTR retries and live worker dispatches before fatal read");
 check(count==0&&head==0,"fatal read discards pending work");
 check(!hc_workspace_swipe_enabled&&!hc_workspace_double_enabled,"fatal read clears Dart claim gates");
 touch(2,1150,875);touch(1,1200,850);
 check(!stock_active&&forwarded==std::vector<int>({0,3})&&count==0&&writes==2,
       "owned swipe drains without queuing an action");
 touch(0,2000);workspace_state.authorize(2000,1);touch(2,2100,900);touch(1,2200,850);
 check(!stock_active&&forwarded==std::vector<int>({0,3,0,2,1})&&count==0,
       "new workspace swipe follows stock path");
 hc_workspace_double_emit();
 check(count==0&&writes==2,"double tap cannot enqueue after fatal read");
 Event back_down{0,0,1,3000,10,500,7},back_move{2,2,1,3050,100,500,7},back_up{1,1,1,3450,100,500,7};
 void* owner=reinterpret_cast<void*>(0x200);
 observe(owner,&back_down,true);observe(owner,&back_move,true);
 auto back=observe(owner,&back_up,true);
 check(!back.cancel&&!back.consume&&!back.gesture,"back quick-switch cannot claim after fatal read");
 hc_workspace_gestures_ready(true);
 check(!start_worker_locked()&&starts==0&&!hc_workspace_swipe_enabled&&!hc_workspace_double_enabled,
       "readiness replay cannot revive an abandoned channel");
 return failures?1:0;
}
