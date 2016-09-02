define(['angular'], function(angular){
	var app = angular.module('apiServiceApp',[]);

	app.factory('apiService', ['$http', '$q', '$log', function($http, $q, $log){
		var getDirectories = function() {
			var deferred = $q.defer();

			// ajax $http
			var headers = {
					'Accept' : 'application/json',
					'Content-Type' : 'application/json'
			};
			var params = {
			};
			var config = {
					'params' : params,
					'headers' : headers
			};

			$http.get(url = '/directories', config).then(function(response) {
				if (response.status == 200) {
					deferred.resolve(response.data);
				} else {
					deferred.reject('getDirectories() fail');
				}
			}, function(response) {
				deferred.reject(response.data);
			}, function(response) {
				deferred.reject(response.data);
			});
			return deferred.promise;
			// then(successCallback, errorCallback, notifyCallback)
		};

		var getDocumentCount = function() {
			var deferred = $q.defer();

			// ajax $http
			var headers = {
					'Accept' : 'application/json',
					'Content-Type' : 'application/json'
			};
			var params = {
			};
			var config = {
					'params' : params,
					'headers' : headers
			};

			$http.get(url = '/documents/count', config).then(function(response) {
				if (response.status == 200) {
					deferred.resolve(response.data);
				} else {
					deferred.reject('getDocumentCount() fail');
				}
			}, function(response) {
				deferred.reject(response.data);
			}, function(response) {
				deferred.reject(response.data);
			});
			return deferred.promise;
			// then(successCallback, errorCallback, notifyCallback)
		};

		var updateSupportType = function(supportTypeDto) {
			var deferred = $q.defer();

			// ajax $http
			var headers = {
					'Accept' : 'application/json',
					'Content-Type' : 'application/json'
			};
			var params = {
			};
			var config = {
					'headers' : headers
			};
			var data = JSON.stringify(supportTypeDto);

			$http.post(url = '/supportType', data, config).then(function(response) {
				if (response.status == 200) {
					deferred.resolve();
				} else {
					deferred.reject();
				}
			}, function(response) {
				deferred.reject();
			}, function(response) {
				deferred.reject();
			});
			return deferred.promise;
			// then(successCallback, errorCallback, notifyCallback)
		};

		var updateDirectories = function(pathList) {
			var deferred = $q.defer();

			// ajax $http
			var headers = {
					'Accept' : 'application/json',
					'Content-Type' : 'application/json'
			};
			var params = {
			};
			var config = {
					'headers' : headers
			};
			var data = JSON.stringify(pathList);

			$http.post(url = '/directories', data, config).then(function(response) {
				if (response.status == 200) {
					deferred.resolve();
				} else {
					deferred.reject();
				}
			}, function(response) {
				deferred.reject();
			}, function(response) {
				deferred.reject();
			});
			return deferred.promise;
			// then(successCallback, errorCallback, notifyCallback)
		};

		return {getDirectories : getDirectories
			,updateDirectories : updateDirectories
			,updateSupportType : updateSupportType
			,getDocumentCount : getDocumentCount};
	}]);
	return app;
});
