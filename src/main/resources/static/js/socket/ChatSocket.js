/**
 * Created by Andrii on 20.06.2015.
 */
'use strict';
servicesModule.factory('ChatSocket', ['$rootScope', function($rootScope) {
    var stompClient;

    var wrappedSocket = {

        init: function(url) {
            stompClient = Stomp.over(new SockJS(url));
        },
        connect: function(successCallback, errorCallback) {

            stompClient.connect({}, function(frame) {
                $rootScope.$apply(function() {
                    successCallback(frame);
                });
            }, function(error) {
                $rootScope.$apply(function(){
                    errorCallback(error);
                });
            });
        },
        subscribe : function(destination, callback) {
            stompClient.subscribe(destination, function(message) {
                $rootScope.$apply(function(){
                    callback(message);
                });
            });
        },
        send: function(destination, headers, object) {
            stompClient.send(destination, headers, object);
        }
    };

    return wrappedSocket;

}]);