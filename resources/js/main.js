
// On-load actions.
$(window).on("load", function() {
  $("body").removeClass("preload");
});

function copySnippet(elem) {
  var div = $(elem.parentElement.parentElement);
  text = "";
  div.find("figure").each(function() {
    var currentText = $(this).text();
    text += currentText;
  });
  copyTextToClipboard(text);
}

function copyTextToClipboard(text) {
  var textArea = document.createElement("textarea");

  textArea.style.position = 'fixed';
  textArea.style.top = 0;
  textArea.style.left = 0;
  textArea.style.width = '2em';
  textArea.style.height = '2em';
  textArea.style.padding = 0;
  textArea.style.border = 'none';
  textArea.style.outline = 'none';
  textArea.style.boxShadow = 'none';
  textArea.style.background = 'transparent';
  textArea.value = text;
  document.body.appendChild(textArea);
  textArea.select();
  try {
    var successful = document.execCommand('copy');
    var msg = successful ? 'successful' : 'unsuccessful';
    // console.log('Copying text command was ' + msg);
  } catch (err) {
    // console.log('Oops, unable to copy');
  }
  document.body.removeChild(textArea);
}

function createTerminal(serverUrl, containerId, terminalId, uid) {
  var appearDuration = 350;
  var revealDuration = 750;
  var bootTerminalDuration = 750;

  $(containerId).animate({
    padding: "8px",
    opacity: 1.0
  }, appearDuration)

  $(terminalId).animate({
    height: "260px"
  }, revealDuration, function() {
    $(terminalId).terminal(function(command) {
      var shell = this;

      if (command !== "") {
        shell.freeze(true);
        var currentPrompt = shell.get_prompt();
        shell.set_prompt("");
        var escapedLine = encodeURIComponent(command);
        var url = serverUrl + "/send-line?uid=" + uid + "&line=" + escapedLine;
        $.get(url).done(function(data) {
          console.log(data);
          data = JSON.parse(data);
          if (data["language"]) {
            currentPrompt = data["language"] + "> ";
          }
          if (data["error"]) {
            shell.echo(data["error"]);
            if (!data["terminate"]) {
              shell.freeze(false);
              shell.set_prompt(currentPrompt);
            }
          } else {
            console.log(data)
            var output = JSON.parse("\"" + data["output"] + "\"");
            shell.echo(output);
            shell.freeze(false);
            shell.set_prompt(currentPrompt);
          }
        }).fail(function(r) {
          shell.echo("Server error code " + r.status + ": " + r.statusText);
          shell.enable();
        })
      } else {
        shell.echo("");
      }
    }, {
      greetings: "GraalVM Shell (type 'js', 'ruby' or 'R' to switch languages)",
      name: "graalvm_shell",
      height: 240,
      prompt: "js> ",
      onInit: function() {
        var shell = this;
        $(terminalId).click(function() {
          console.log("Focused terminal.");
          shell.focus(true);
        });
        shell.disable();
        shell.enable();

        // window.addEventListener("keydown", function(e) {
        //   if (e.keyCode == 32 && e.target == document.body) {
        //     // $(terminalId).keydown()[0].focus();
        //     // e.preventDefault();
        //   }
        // });
      }
    });

    $(terminalId).animate({
      opacity: 1.0
    }, bootTerminalDuration);
  });
}

function establishSessionAndCreateTerminal(serverUrl) {
  console.log("Establishing shell session with " + serverUrl);
  $.get(serverUrl + "/start-session").done(function(data) {
    console.log(data);
    data = JSON.parse(data);
    if (data["error"]) {
      console.error(data["error"]);
    } else {
      console.log("Starting terminal session...");
      createTerminal(serverUrl, "#terminal-container", "#terminal", data["uid"]);
    }
  }).fail(function(r) {
    console.error(r);
  });
}

$(document).ready(function() {

  // highlightjs init
  $('pre code').each(function(i, block) {
    hljs.highlightBlock(block);
  });

  // slick slider init
  (function() {
    $('.video-carousel').slick({
      dots: true,
      responsive: [
        {
          breakpoint: 900,
          settings: {
            arrows: false,
            adaptiveHeight: true
          }
        }
      ]
    });
  }());

  var configProfile = {
    "profile": {"screenName": 'thomaswue'},
    "domId": 'tweets',
    "maxTweets": 10,
    "enableLinks": true,
    "showUser": false,
    "showTime": true,
    "showImages": false,
    "lang": 'en',
    "customCallback": function(data){
      var el = $("#tweets");
      for(var i in data) {
        el.append('<div>'+data[i]+'</div>');
      }
      el.slick({
        dots: false,
        infinite: true,
        slidesToShow: 3,
        slidesToScroll: 1,
        arrows: true,
        autoplaySpeed: 3000,
        responsive: [
          {
            breakpoint: 1280,
            settings: {
              arrows: false
            }
          },
          {
            breakpoint: 768,
            settings: {
              arrows: false,
              adaptiveHeight: true,
              slidesToShow: 1
            }
          }
        ]
      });
    }
  };
  twitterFetcher.fetch(configProfile);

  (function() {
    var topBanner = $('.home-banner');
    var header = $('.header');
    var topBannerHeight = topBanner.innerHeight();
    var showed = false;

    $(window).scroll(function() {
      var scrollTop = $(document).scrollTop();

      if (scrollTop > topBannerHeight && !showed) {
        header.addClass('header--filled animated fadeIn');
        showed = true;
      } else if (scrollTop <= topBannerHeight && showed) {
        header.removeClass('header--filled animated fadeIn').addClass('header--filled animated fadeOut');
        setTimeout(function() {
          header.removeClass('header--filled animated fadeOut');
        }, 500);
        showed = false;
      }
    });
  }());

  // stiky content menu
  (function() {
    var headerHeight = $('.header').innerHeight();
    var sectionHeadingHeight = $('.section-heading').innerHeight();
    var offsetTop = parseInt(headerHeight) + parseInt(sectionHeadingHeight);

    var contentMenuHorixontal = $(".toc-bullets--horizontal");
    var showed = false;

    $(window).scroll(function() {
      var scrollTop = $(document).scrollTop();

      if (scrollTop > offsetTop && !showed) {
        contentMenuHorixontal.addClass('toc-bullets--horizontal-stiky');
        showed = true;
      } else if (scrollTop <= sectionHeadingHeight && showed) {
        contentMenuHorixontal.removeClass('toc-bullets--horizontal-stiky');
        showed = false;
      }
    });
  }());


  // mobile menu
  if ($('.js-show-menu').length) {
    $(".js-show-menu, .overlay").click(function(e) {
      e.preventDefault();
      $('body').toggleClass('overflow-hidden');
      $(".menu-btn--menu").toggleClass('menu-open').toggleClass('close');
      $('.menu__list').toggleClass('open');
      $('.menu__logo').toggleClass('open');
      $('.menu').toggleClass('open');
      $('.overlay').fadeToggle();
    });
  }

  if($(".js-show-sidebar").length) {
    $(".js-show-sidebar, .overlay").click(function(e) {
      e.preventDefault();
      $('body').toggleClass('overflow-hidden');
      $(".menu-btn--sidebar").toggleClass('menu-open');
      $('.sidebar').toggleClass('open');
      $('.overlay').fadeToggle();
    });
  }

  if (window.location.href.toString().split(window.location.host)[1] === '/docs/faq/') {
    var contentWrapp = $('#content-wrapper');

    var allIds = contentWrapp.find($("*[id]:not([id='graalvm---run-any-language-anywhere'])"));

    allIds.addClass('title-faq');
    allIds.nextUntil("h3").hide();

    allIds.click(function () {
      $(this).toggleClass("title-faq--opened");
      $(this).nextUntil("h3").slideToggle();
    });
  }
});


// if (window.location.href.toString().split(window.location.host)[1] === '/docs/faq/') {
//   var content = document.getElementById('content-wrapper');
//   var allID = content.querySelectorAll('*[id]:not([id="graalvm---run-any-language-anywhere"])');
//   var header = content.querySelector('[id]');
//
//   var nextUntil = function (elem, selector) {
//     var siblings = [];
//     elem = elem.nextElementSibling;
//     while (elem) {
//       if (elem.matches(selector)) break;
//       siblings.push(elem);
//       elem = elem.nextElementSibling;
//     }
//
//     return siblings;
//
//   };
//
//   function wrap(el, wrapper) {
//     el.parentNode.insertBefore(wrapper, el);
//     wrapper.appendChild(el);
//   }
//
//   allID.forEach(function(item, value) {
//     item.classList.add('title-faq');
//     item.nextElementSibling.style.display = 'none';
//
//     item.addEventListener('click', function () {
//       this.classList.toggle('title-faq--opened');
//       this.nextElementSibling.classList.toggle('visible');
//     })
//   });
// }

// stiky sidebar
var sidebar = document.querySelector('.sidebar-wrap');

if (sidebar) {
  var stickySidebar = new StickySidebar(sidebar, {
    topSpacing: 74,
    bottomSpacing: 40
  });

  sidebar.addEventListener('affix.container-bottom.stickySidebar', function (event) {
    window.dispatchEvent(new Event('resize'));
  });
}


// video popup
var modal = document.getElementById('video-view');
var btnsArray = document.querySelectorAll(".js-popup");
var closeModal = document.getElementById("js-close");
var btn;

[].forEach.call(btnsArray,function(el){
  el.addEventListener('click', function (e) {
    e.preventDefault();
    var videoIndex = this.dataset.video;
    modal.style.display = "flex";
    loadVideo(videoIndex);
  })
});

if (closeModal) {
  closeModal.onclick = function() {
    modal.style.display = "none";
    stopVideo();
  };
}

window.onclick = function(event) {
  if (event.target == modal) {
    modal.style.display = "none";
    stopVideo();
  }
};

document.addEventListener('keyup', function(event) {
  if (event.keyCode == 27) {
    modal.style.display = "none";
    stopVideo();
  }
});

var tag = document.createElement('script');

tag.src = "https://www.youtube.com/iframe_api";
var firstScriptTag = document.getElementsByTagName('script')[0];
firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

var player, lastVideoId = '';
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    height: 'auto',
    width: 'auto',
    events: {
      'onReady': onPlayerReady,
      'onStateChange': onPlayerStateChange
    }
  });
}

function loadVideo(id) {
  if (player && id != lastVideoId) {
    player.loadVideoById(id);
    lastVideoId = id;
  } else {
    player.playVideo();
  }
}

function onPlayerReady(event) {
  event.target.playVideo();
}

var done = false;

function onPlayerStateChange(event) {
  if (event.data == YT.PlayerState.PLAYING && !done) {
    setTimeout(stopVideo, 1000);
    done = true;
  }
}
function stopVideo() {
  player.pauseVideo();
}

function clearResults() {
  var clearBtn = document.getElementById('clear-btn');

  if (clearBtn) {
    clearBtn.addEventListener("click", function () {
      document.getElementById('module-name').value = '';
      document.getElementById('no-results').style.display = "none";

      var resultsTable = document.querySelectorAll('.results-table .table');
      resultsTable.forEach(function (value) {
        value.style.display = "none";
      });
    });
  }
}

clearResults();
