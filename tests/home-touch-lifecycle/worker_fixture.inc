#include <cerrno>
#include <pthread.h>
#include <sys/eventfd.h>
#include <time.h>
#include <unistd.h>

unsigned reads=0,writes=0,starts=0;
void emit(int action,uint64_t revision);
ssize_t fixture_read(int,void* buffer,size_t bytes){
 ++reads;
 if(reads==1){errno=EINTR;return -1;}
 if(reads==2){std::memset(buffer,0,bytes);return bytes;}
 // A producer can queue work just before the failed read is observed.
 emit(home_gestures::Down,settings.revision);
 errno=EIO;
 return -1;
}
ssize_t fixture_write(int,const void*,size_t bytes){++writes;return bytes;}
int fixture_eventfd(unsigned,int){return 1;}
int fixture_create(pthread_t*,const pthread_attr_t*,void*(*)(void*),void*){++starts;return 0;}
int fixture_detach(pthread_t){return 0;}
int fixture_close(int){return 0;}
void AIBinder_decStrong(AIBinder*){}
void send(Request request){emitted.push_back(request.event);}
#define read fixture_read
#define write fixture_write
#define eventfd fixture_eventfd
#define pthread_create fixture_create
#define pthread_detach fixture_detach
#define close fixture_close
