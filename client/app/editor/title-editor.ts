/*
 * Copyright (c) 2015-2016 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/// <reference path="../../typedefs/react/react.d.ts" />
/// <reference path="../../typedefs/modernizr/modernizr.d.ts" />
/// <reference path="../plain-old-javascript.d.ts" />
/// <reference path="../model.ts" />
/// <reference path="../Server.ts" />
/// <reference path="editor-utils.ts" />

//------------------------------------------------------------------------------
   module debiki2.titleeditor {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var reactCreateFactory = React['createFactory'];
var ReactCSSTransitionGroup = reactCreateFactory(React.addons.CSSTransitionGroup);
var ReactBootstrap: any = window['ReactBootstrap'];
var Button = reactCreateFactory(ReactBootstrap.Button);
var Input = reactCreateFactory(ReactBootstrap.Input);
var $: any = window['jQuery'];


export var TitleEditor = createComponent({
  getInitialState: function() {
    return {
      showComplicated: false,
      isSaving: false,
      pageRole: this.props.pageRole,
    };
  },

  componentDidMount: function() {
    var store: Store = this.props;
    Server.loadEditorEtceteraScripts().done(() => {
      if (!this.isMounted()) return;
      this.setState({ editorScriptsLoaded: true });
    });
  },

  showComplicated: function() {
    var store: Store = this.props;
    var pagePath: PagePath = store.pagePath;
    this.setState({
      showComplicated: true,
      folder: pagePath.folder,
      slug: pagePath.slug,
      showId: pagePath.showId,
    });
  },

  onTitleChanged: function(event) {
    var store: Store = this.props;
    var idWillBeInUrlPath = this.refs.showIdInput ?
        this.refs.showIdInput.getChecked() : store.pagePath.showId; // isIdShownInUrl();
    if (!idWillBeInUrlPath) {
      // Then don't automatically change the slug to match the title, because links are more fragile
      // when no id included in the url, and might break if we change the slug. Also, the slug is likely
      // to be something like 'about' (for http://server/about) which we want to keep unchanged.
      return;
    }
    var editedTitle = event.target.value;
    var slugMatchingTitle = window['debikiSlugify'](editedTitle);
    this.setState({ slug: slugMatchingTitle });
  },

  onPageRoleChanged: function(event) {
    this.setState({ pageRole: parseInt(event.target.value) });
  },

  onFolderChanged: function(event) {
    this.setState({ folder: event.target.value });
  },

  onSlugChanged: function(event) {
    this.setState({ slug: event.target.value });
  },

  onShowIdChanged: function(event) {
    this.setState({ showId: event.target.checked });
  },

  save: function() {
    this.setState({ isSaving: true });
    var newTitle = this.refs.titleInput.getValue();
    var pageSettings = this.getSettings();
    ReactActions.editTitleAndSettings(newTitle, pageSettings, this.props.closeEditor, () => {
      this.setState({ isSaving: false });
    });
  },

  getSettings: function() {
    var categoryInput = this.refs.categoryInput;
    var settings: any = {
      categoryId: categoryInput ? parseInt(categoryInput.getValue()) : null,
      pageRole: this.state.pageRole,
      folder: addFolderSlashes(this.state.folder),
      slug: this.state.slug,
      showId: this.state.showId
    };
    if (this.refs.layoutInput) {
      settings.layout = this.refs.layoutInput.getValue();
    }
    return settings;
  },

  render: function() {
    var store: Store = this.props;
    var pageRole: PageRole = this.props.pageRole;
    var titlePost: Post = this.props.allPosts[TitleId];
    var titleText = titlePost.sanitizedHtml; // for now. TODO only allow plain text?
    var user = this.props.user;
    var isForumOrAbout = pageRole === PageRole.Forum || pageRole === PageRole.About;

    if (!this.state.editorScriptsLoaded) {
      // The title is not shown, so show some whitespace to avoid the page jumping upwards.
      return r.div({ style: { height: 80 }});
    }

    var complicatedStuff;
    if (this.state.showComplicated) {
      var dashId = this.state.showId ? '-' + debiki.getPageId() : '';
      var slashSlug =  this.state.slug;
      if (dashId && slashSlug) slashSlug = '/' + slashSlug;
      var url = location.protocol + '//' + location.host +
          addFolderSlashes(this.state.folder) + dashId + slashSlug;

      var anyUrlEditor = !store.settings.showComplicatedStuff ? null :
        r.div({ className: 'esTtlEdtr_urlSettings' },
          r.p({}, r.b({}, "Ignore this "), "unless you understand URL addresses."),
          Input({ label: 'Page slug', type: 'text', ref: 'slugInput', className: 'dw-i-slug',
            labelClassName: 'col-xs-2', wrapperClassName: 'col-xs-10',
            value: this.state.slug, onChange: this.onSlugChanged,
            help: "The name of this page in the URL."}),
          Input({ label: 'Folder', type: 'text', ref: 'folderInput', className: 'dw-i-folder',
            labelClassName: 'col-xs-2', wrapperClassName: 'col-xs-10',
            value: this.state.folder, onChange: this.onFolderChanged,
            help: "Any /url/path/segments/ to this page." }),
          Input({ label: 'Show page ID in URL', type: 'checkbox', ref: 'showIdInput',
            wrapperClassName: 'col-xs-offset-2 col-xs-10',
            className: 'dw-i-showid', checked: this.state.showId,
            onChange: this.onShowIdChanged }),
          r.p({}, "The page URL will be: ", r.kbd({}, url)));

      complicatedStuff =
        r.div({},
          r.div({ className: 'dw-compl-stuff form-horizontal', key: 'compl-stuff-key' },
            isForumOrAbout ? null :
              editor.PageRoleInput({ me: store.me, value: this.state.pageRole,
                label: "Page type", labelClassName: 'col-xs-2', wrapperClassName: 'col-xs-10',
                onChange: this.onPageRoleChanged,
                complicated: store.settings.showComplicatedStuff,
                title: 'Page type', className: 'esEdtr_titleEtc_pageRole',
                help: "Makes the page behave differently. For example, pages of type Question " +
                  "can be marked as solved. And a To-Do as doing and done." }),
            anyUrlEditor));
    }

    // Once the complicated stuff has been shown, one cannot hide it, except by cancelling
    // the whole dialog. Because if hiding it, then what about any changes made? Save or ignore?
    var showAdvancedButton = this.state.showComplicated || !user.isAdmin
        ? null
        : r.a({ className: 'dw-toggle-compl-stuff icon-settings',
            onClick: this.showComplicated }, 'Advanced');

    var selectCategoryInput;
    if (isForumOrAbout) {
      // About-category pages cannot be moved to other categories.
    }
    else if (this.props.forumId) {
      var categoryOptions = this.props.categories.map((category: Category) => {
        return r.option({ value: category.id, key: category.id }, category.name);
      });

      selectCategoryInput =
        Input({ type: 'select', label: 'Category', ref: 'categoryInput', title: 'Category',
            labelClassName: 'col-xs-2', wrapperClassName: 'col-xs-10',
            defaultValue: this.props.categoryId },
          categoryOptions);
    }

    var customHtmlPageOption = user.isAdmin
        ? r.option({ value: PageRole.HomePage }, 'Custom HTML page')
        : null;

    var addBackForumIntroButton;
    if (this.props.pageRole === PageRole.Forum) {
      var introPost = this.props.allPosts[BodyId];
      var hasIntro = introPost && introPost.sanitizedHtml && !introPost.isPostHidden;
      if (!hasIntro) {
        addBackForumIntroButton =
            r.a({ className: 'icon-plus', onClick: () => {
              ReactActions.setPostHidden(BodyId, false);
              debiki2.ReactActions.showForumIntro(true);
            }}, "Add forum intro text");
      }
    }

    var saveCancel = this.state.isSaving
      ? r.div({}, 'Saving...')
      : r.div({ className: 'dw-save-btns-etc' },
          Button({ onClick: this.save, bsStyle: 'primary', className: 'e2eSaveBtn' }, 'Save'),
          Button({ onClick: this.props.closeEditor, className: 'e2eCancelBtn' }, 'Cancel'),
          showAdvancedButton, addBackForumIntroButton);

    return (
      r.div({ className: 'dw-p-ttl-e' },
        Input({ type: 'text', ref: 'titleInput', className: 'dw-i-title', id: 'e2eTitleInput',
            defaultValue: titleText, onChange: this.onTitleChanged }),
        r.div({ className: 'dw-page-category-role form-horizontal' },
          selectCategoryInput),
        ReactCSSTransitionGroup({ transitionName: 'compl-stuff',
            transitionAppear: true, transitionAppearTimeout: 600,
            transitionEnterTimeout: 600, transitionLeaveTimeout: 500 },
          complicatedStuff),
        saveCancel));
  }
});


function addFolderSlashes(folder) {
  if (folder || folder === '') {
    if (folder[folder.length - 1] !== '/') folder = folder + '/';
    if (folder[0] !== '/') folder = '/' + folder;
  }
  return folder;
}

//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
